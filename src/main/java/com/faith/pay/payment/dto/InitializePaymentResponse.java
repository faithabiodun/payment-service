package com.faith.pay.payment.dto;

import com.faith.pay.payment.Payment;

public record InitializePaymentResponse(
        String reference,
        Long orderId,
        long amountKobo,
        String currency,
        String status,
        String authorizationUrl,
        String accessCode
) {
    public static InitializePaymentResponse from(Payment payment) {
        return new InitializePaymentResponse(
                payment.getReference(),
                payment.getOrder().getId(),
                payment.getAmountKobo(),
                payment.getCurrency(),
                payment.getStatus().name(),
                payment.getAuthorizationUrl(),
                payment.getAccessCode()
        );
    }
}
