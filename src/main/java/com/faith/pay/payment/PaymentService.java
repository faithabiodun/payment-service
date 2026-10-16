package com.faith.pay.payment;

import com.faith.pay.error.*;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.payment.dto.*;
import com.faith.pay.provider.PaystackClient;
import com.faith.pay.provider.dto.PaystackInitializeData;
import com.faith.pay.provider.dto.PaystackTransactionData;
import com.faith.pay.provider.dto.VerifyPaymentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private final PaymentCommandService commands;

    private final PaymentRepository payments;

    private final PaystackClient paystackClient;

    private final PaymentSettlementService settlement;


    public PaymentService(
            PaymentCommandService commands,
            PaymentRepository payments,
            PaystackClient paystackClient,
            PaymentSettlementService settlement
    ) {

        this.commands = commands;

        this.payments = payments;

        this.paystackClient = paystackClient;

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


        PaymentReservation reservation = commands.reserve(request.orderId(), idempotencyKey);

        if (!reservation.newlyCreated()) {
            return response(reservation);
        }


        try {
            PaystackInitializeData provider = paystackClient.initialize(
                    reservation.email(), reservation.amountKobo(), reservation.reference());

            return response(commands.markInitialized(reservation.reference(), provider));

        } catch (ProviderCommunicationException failure) {
            // Paystack may have accepted the request before the connection failed.
            // Keep the reservation and record that the outcome needs reconciliation.
            commands.markInitializationUncertain(reservation.reference());
            throw failure;
        }
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

        PaystackTransactionData provider = paystackClient.verify(reference);


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

        if ("success".equalsIgnoreCase(provider.status())) {

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
                provider.status(),
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
