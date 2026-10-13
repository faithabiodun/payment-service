package com.faith.pay.payment.dto;

import com.faith.pay.payment.Payment;
import com.faith.pay.payment.PaymentStatus;

public record InitializePaymentResponse(
        Long paymentId,
        String reference,
        long amountKobo,
        String currency,
        PaymentStatus status,
        String authorizationUrl
) {
    public static InitializePaymentResponse from(Payment payment) {
        return new InitializePaymentResponse(
                payment.getId(),
                payment.getReference(),
                payment.getAmountKobo(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getAuthorizationUrl()
        );
    }
}
