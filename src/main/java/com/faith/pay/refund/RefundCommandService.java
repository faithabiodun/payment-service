package com.faith.pay.refund;

import com.faith.pay.error.*;
import com.faith.pay.payment.*;
import com.faith.pay.payment.audit.*;
import com.faith.pay.refund.dto.RefundRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class RefundCommandService {

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentEventStore events;

    public RefundCommandService(
            RefundRepository refunds,
            PaymentRepository payments,
            PaymentEventStore events
    ) {
        this.refunds = refunds;
        this.payments = payments;
        this.events = events;
    }

    @Transactional
    public RefundReservation reserve(
            String paymentReference,
            RefundRequest request,
            String idempotencyKey
    ) {
        validateInputs(paymentReference, idempotencyKey);
        if (request == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                    "Refund request is required");
        }
        if (request.amountKobo() != null && request.amountKobo() <= 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                    "Refund amount must be positive");
        }

        Refund existing = refunds.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            validateReplay(existing, paymentReference, request, idempotencyKey);
            return reservation(existing, false);
        }

        Payment payment = payments.findByReferenceForUpdate(paymentReference)
                .orElseThrow(() -> new PaymentNotFoundException(paymentReference));

        // Recheck after taking the payment lock to serialize competing refunds.
        existing = refunds.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            validateReplay(existing, paymentReference, request, idempotencyKey);
            return reservation(existing, false);
        }

        if (payment.getStatus() != PaymentStatus.SUCCESS
                && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new RefundNotAllowedException("Only successful payments can be refunded");
        }

        long reserved = refunds.sumReservedAmount(payment.getId());
        long remaining = payment.getAmountKobo() - reserved;
        long requested = request.amountKobo() == null ? remaining : request.amountKobo();

        if (requested <= 0) {
            throw new RefundNotAllowedException("No refundable balance remains");
        }
        if (requested > remaining) {
            throw new RefundAmountExceedsRemainingException(requested, remaining);
        }

        Refund refund = new Refund(
                payment,
                newReference(),
                requested,
                request.amountKobo(),
                request.reason(),
                idempotencyKey
        );
        refunds.saveAndFlush(refund);
        events.append(payment, PaymentEventType.REFUND_REQUESTED,
                payment.getStatus(), payment.getStatus(), PaymentEventSource.REFUND, null);
        return reservation(refund, true);
    }

    private void validateInputs(String paymentReference, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                    "Idempotency-Key is required");
        }
        if (paymentReference == null || paymentReference.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                    "Payment reference is required");
        }
    }

    private void validateReplay(
            Refund refund,
            String paymentReference,
            RefundRequest request,
            String idempotencyKey
    ) {
        boolean amountMatches = Objects.equals(
                refund.getRequestedAmountKobo(),
                request.amountKobo()
        );
        if (!refund.getPayment().getReference().equals(paymentReference)
                || !amountMatches
                || !Objects.equals(refund.getReason(), request.reason())
                || !refund.getIdempotencyKey().equals(idempotencyKey)) {
            throw new IdempotencyKeyReuseException();
        }
    }

    private RefundReservation reservation(Refund refund, boolean newlyCreated) {
        return new RefundReservation(refund.getId(), refund.getReference(),
                refund.getPayment().getReference(), refund.getAmountKobo(), refund.getStatus(),
                refund.getProviderRefundId(), newlyCreated);
    }

    private String newReference() {
        return "REF-" + UUID.randomUUID().toString().replace("-", "");
    }
}
