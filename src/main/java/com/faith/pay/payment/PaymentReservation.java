package com.faith.pay.payment;

import com.faith.pay.provider.core.PaymentProviderType;

/** Internal result of reserving a payment before contacting the provider. */
public record PaymentReservation(
        Long paymentId,
        Long orderId,
        String email,
        long amountKobo,
        String currency,
        String reference,
        PaymentProviderType provider,
        PaymentStatus status,
        String authorizationUrl,
        boolean newlyCreated
) {
}
