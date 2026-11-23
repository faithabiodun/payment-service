package com.faith.pay.payment;

import com.faith.pay.error.*;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.payment.dto.*;
import com.faith.pay.provider.core.*;
import com.faith.pay.provider.dto.VerifyPaymentResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private final PaymentCommandService commands;

    private final PaymentRepository payments;

    private final PaymentProviderRegistry providers;

    private final ProviderRoutingPolicy routingPolicy;

    private final PaymentSettlementService settlement;


    public PaymentService(
            PaymentCommandService commands,
            PaymentRepository payments,
            PaymentProviderRegistry providers,
            ProviderRoutingPolicy routingPolicy,
            PaymentSettlementService settlement
    ) {

        this.commands = commands;

        this.payments = payments;

        this.providers = providers;

        this.routingPolicy = routingPolicy;

        this.settlement = settlement;
    }


    /** Coordinates reservation and provider I/O without holding a database transaction open. */
    public InitializePaymentResponse initialize(
            InitializePaymentRequest request,
            String idempotencyKey
    ) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                    "Idempotency-Key is required");
        }
        if (idempotencyKey.length() > 100) {
            throw new AppException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be at most 100 characters");
        }

        // Serve idempotent replays before routing or provider circuit/config checks.
        var existing = payments.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayResponse(existing.get(), request);
        }

        PaymentProviderType selectedProvider = routingPolicy.choose(request.provider());
        PaymentProvider provider = providers.get(selectedProvider);
        provider.validateConfiguration();

        PaymentReservation reservation;
        try {
            reservation = commands.reserve(request.orderId(), idempotencyKey, selectedProvider);
        } catch (DataIntegrityViolationException conflict) {
            var raced = payments.findByIdempotencyKey(idempotencyKey);
            if (raced.isPresent()) {
                return replayResponse(raced.get(), request);
            }
            throw conflict;
        }

        if (!reservation.newlyCreated()) {
            if (reservation.status() == PaymentStatus.PENDING) {
                throw new PaymentInitializationInProgressException(request.orderId());
            }
            return response(reservation);
        }


        try {
            ProviderPaymentResult result = provider.initialize(new ProviderPaymentRequest(
                    reservation.reference(), reservation.email(), reservation.amountKobo(),
                    reservation.currency(), null, idempotencyKey));

            return response(commands.markInitialized(reservation.reference(), result));

        } catch (RuntimeException failure) {
            // The provider may have accepted the request before the failure occurred.
            // Keep the reservation and record that the outcome needs reconciliation.
            commands.markInitializationUncertain(reservation.reference());
            throw failure;
        }
    }

    private InitializePaymentResponse replayResponse(Payment payment, InitializePaymentRequest request) {
        if (!payment.getOrder().getId().equals(request.orderId())
                || (request.provider() != null && payment.getProvider() != request.provider())) {
            throw new IdempotencyKeyReuseException();
        }
        if (payment.getStatus() == PaymentStatus.PENDING) {
            throw new PaymentInitializationInProgressException(request.orderId());
        }
        return new InitializePaymentResponse(payment.getId(), payment.getReference(),
                payment.getAmountKobo(), payment.getCurrency(), payment.getStatus(),
                payment.getAuthorizationUrl());
    }


    public PaymentResponse get(String reference) {
        Payment payment = payments.findByReference(reference)
                .orElseThrow(() -> new PaymentNotFoundException(reference));
        return PaymentResponse.from(payment);
    }


    public VerifyPaymentResponse verify(String reference) {

        /*
         * ------------------------------
         * NO DATABASE TRANSACTION
         * ------------------------------
         *
         * External HTTP request.
         */

        Payment payment = payments.findByReference(reference)
                .orElseThrow(() -> new PaymentNotFoundException(reference));
        String providerId = payment.getProviderTransactionId();
        if (providerId == null || providerId.isBlank()) {
            // Before settlement, Stripe knows its Checkout Session ID while the
            // PaymentIntent ID is only available after the customer pays.
            providerId = payment.getAccessCode();
        }
        VerifiedPayment provider = providers.get(payment.getProvider()).verify(reference, providerId);


        /*
         * Defend against an impossible/malformed
         * provider response before doing anything.
         */

        if (provider.reference() == null || !reference.equals(provider.reference())) {
            throw new PaymentReferenceMismatchException(reference, provider.reference());
        }


        boolean applied = false;


        /*
         * Do not confuse:
         *
         * API request succeeded
         *
         * with:
         *
         * transaction succeeded.
         */

        if (provider.succeeded()) {

            SettlementResult result = settlement.settleSuccess(
                    reference,
                    provider,
                    PaymentEventSource.VERIFICATION,
                    null
            );

            applied = result == SettlementResult.APPLIED;
        }


        /*
         * If provider says:
         *
         * pending
         * ongoing
         * abandoned
         * processing
         * failed
         *
         * don't pretend it succeeded.
         *
         * For now we leave the local payment
         * unchanged.
         */

        return new VerifyPaymentResponse(
                    provider.state().name(),
                applied,
                get(reference)
        );
    }


    private InitializePaymentResponse response(PaymentReservation reservation) {
        return new InitializePaymentResponse(reservation.paymentId(), reservation.reference(),
                reservation.amountKobo(), reservation.currency(), reservation.status(),
                reservation.authorizationUrl());
    }
}
