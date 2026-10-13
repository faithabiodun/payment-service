package com.faith.pay.payment;

/** Internal result of reserving a payment before contacting the provider. */
public record PaymentReservation(
        Long paymentId,
        Long orderId,
        String email,
        long amountKobo,
        String currency,
        String reference,
        PaymentStatus status,
        String authorizationUrl,
        boolean newlyCreated
) {
}
