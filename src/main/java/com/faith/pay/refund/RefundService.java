package com.faith.pay.refund;

import com.faith.pay.error.AppException;
import com.faith.pay.error.ErrorCode;
import com.faith.pay.error.PaymentNotFoundException;
import com.faith.pay.error.ProviderCommunicationException;
import com.faith.pay.payment.Payment;
import com.faith.pay.payment.PaymentRepository;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.core.*;
import com.faith.pay.refund.dto.RefundRequest;
import com.faith.pay.refund.dto.RefundResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RefundService {

    private final RefundCommandService commands;
    private final RefundStateService refundStates;
    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final RefundProviderRegistry providers;

    public RefundService(
            RefundCommandService commands,
            RefundStateService refundStates,
            RefundRepository refunds,
            PaymentRepository payments,
            RefundProviderRegistry providers
    ) {
        this.commands = commands;
        this.refundStates = refundStates;
        this.refunds = refunds;
        this.payments = payments;
        this.providers = providers;
    }

    /** Reserves locally, then calls the selected provider after the reservation transaction commits. */
    public RefundResponse refund(String paymentReference, RefundRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                    "Idempotency-Key is required");
        }
        if (idempotencyKey.length() > 100) {
            throw new AppException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be at most 100 characters");
        }
        if (paymentReference == null || paymentReference.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                    "Payment reference is required");
        }
        if (request == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                    "Refund request is required");
        }
        if (request.amountKobo() != null && request.amountKobo() <= 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                    "Refund amount must be positive");
        }

        // Serve idempotent replays before provider configuration or circuit-breaker checks.
        Refund existing = refunds.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            commands.validateReplay(existing, paymentReference, request, idempotencyKey);
            return RefundResponse.from(existing);
        }

        Payment payment = payments.findByReference(paymentReference)
                .orElseThrow(() -> new PaymentNotFoundException(paymentReference));
        RefundProvider provider = providers.get(payment.getProvider());
        provider.validateConfiguration();

        RefundReservation reservation;
        try {
            reservation = commands.reserve(paymentReference, request, idempotencyKey);
        } catch (DataIntegrityViolationException conflict) {
            Refund raced = refunds.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (raced != null) {
                commands.validateReplay(raced, paymentReference, request, idempotencyKey);
                return RefundResponse.from(raced);
            }
            throw conflict;
        }

        if (!reservation.newlyCreated()) {
            return get(reservation.refundReference());
        }

        // Use the fresh locked provider details captured during reservation to avoid TOCTOU races.
        RefundProvider reservedProvider = providers.get(reservation.provider());
        try {
            ProviderRefundResult result = reservedProvider.refund(new ProviderRefundRequest(
                    reservation.paymentReference(), reservation.providerTransactionId(), reservation.refundReference(),
                    reservation.amountKobo(), reservation.currency(), request.reason(), idempotencyKey));
            VerifiedRefund normalized = new VerifiedRefund(result.provider(), result.providerRefundId(),
                    result.refundReference(), result.state(), result.amountMinor(), result.currency(), result.providerStatus());
            return RefundResponse.from(refundStates.applyVerifiedState(
                    reservation.refundReference(),
                    normalized,
                    PaymentEventSource.REFUND,
                    null
            ));
        } catch (ProviderCommunicationException failure) {
            // Keep the committed reservation so reconciliation can recover an uncertain result.
            throw failure;
        }
    }

    private RefundResponse get(String reference) {
        return refunds.findByReference(reference)
                .map(RefundResponse::from)
                .orElseThrow(() -> new AppException(ErrorCode.REFUND_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "Refund not found: " + reference));
    }
}
