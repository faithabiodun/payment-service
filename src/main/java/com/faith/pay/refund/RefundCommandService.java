package com.faith.pay.refund;

import com.faith.pay.error.*;
import com.faith.pay.payment.*;
import com.faith.pay.payment.audit.*;
import com.faith.pay.refund.dto.RefundRequest;
import com.faith.pay.provider.dto.PaystackRefundData;
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

        if (!payment.getStatus().hasSuccessfulCharge()) {
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

    @Transactional
    public Refund applyProviderResult(String refundReference, PaystackRefundData provider) {
        Refund refund = refunds.findByReferenceForUpdate(refundReference)
                .orElseThrow(() -> new AppException(
                        ErrorCode.REFUND_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Refund not found: " + refundReference
                ));

        Payment payment = payments.findByReferenceForUpdate(refund.getPayment().getReference())
                .orElseThrow(() -> new PaymentNotFoundException(refund.getPayment().getReference()));

        if (refund.getStatus().isTerminal()) {
            return refund;
        }

        String providerStatus = provider.status();
        if (providerStatus == null || providerStatus.isBlank()) {
            throw new AppException(ErrorCode.PROVIDER_RESPONSE_INVALID, HttpStatus.BAD_GATEWAY,
                    "Paystack returned a refund without a status");
        }
        if (provider.id() == null) {
            throw new AppException(ErrorCode.PROVIDER_RESPONSE_INVALID, HttpStatus.BAD_GATEWAY,
                    "Paystack returned a refund without an id");
        }

        PaymentStatus paymentStatus = payment.getStatus();
        String normalized = providerStatus.toLowerCase();
        events.append(payment, PaymentEventType.REFUND_SUBMITTED,
                paymentStatus, paymentStatus, PaymentEventSource.REFUND, null);
        if ("processed".equals(normalized) || "success".equals(normalized)) {
            if (provider.amount() != refund.getAmountKobo()) {
                throw new AppException(ErrorCode.PROVIDER_RESPONSE_INVALID, HttpStatus.BAD_GATEWAY,
                        "Paystack refund amount did not match the reserved amount");
            }
            refund.markSuccessful(providerStatus);
            payment.addRefundedKobo(refund.getAmountKobo());
            events.append(payment, PaymentEventType.REFUND_SUCCEEDED,
                    paymentStatus, payment.getStatus(), PaymentEventSource.REFUND, null);
        } else if ("failed".equals(normalized)) {
            refund.markFailed(providerStatus);
            events.append(payment, PaymentEventType.REFUND_FAILED,
                    paymentStatus, paymentStatus, PaymentEventSource.REFUND, null);
        } else if ("needs_attention".equals(normalized) || "needs-attention".equals(normalized)) {
            refund.markNeedsAttention(providerStatus);
            events.append(payment, PaymentEventType.REFUND_NEEDS_ATTENTION,
                    paymentStatus, paymentStatus, PaymentEventSource.REFUND, null);
        } else {
            refund.markProcessing(provider.id().toString(), providerStatus);
            events.append(payment, PaymentEventType.REFUND_PROCESSING,
                    paymentStatus, paymentStatus, PaymentEventSource.REFUND, null);
        }

        return refund;
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
