package com.faith.pay.payment;

import com.faith.pay.error.*;
import org.springframework.http.HttpStatus;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.payment.dto.*;
import com.faith.pay.provider.PaystackClient;
import com.faith.pay.provider.dto.PaystackInitializeData;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private final PaymentCommandService commands;
    private final PaymentRepository payments;
    private final PaystackClient paystackClient;
    private final PaymentCompletionService completionService;

    public PaymentService(
            PaymentCommandService commands,
            PaymentRepository payments,
            PaystackClient paystackClient,
            PaymentCompletionService completionService
    ) {
        this.commands = commands;
        this.payments = payments;
        this.paystackClient = paystackClient;
        this.completionService = completionService;
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

    public PaymentResponse verify(String reference) {
        var provider = paystackClient.verify(reference);
        if ("success".equalsIgnoreCase(provider.status())) {
            completionService.applySuccess(provider, PaymentEventSource.VERIFICATION, null);
        }
        return get(reference);
    }

    private InitializePaymentResponse response(PaymentReservation reservation) {
        return new InitializePaymentResponse(reservation.paymentId(), reservation.reference(),
                reservation.amountKobo(), reservation.currency(), reservation.status(),
                reservation.authorizationUrl());
    }
}
