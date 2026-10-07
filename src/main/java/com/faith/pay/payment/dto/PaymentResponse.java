package com.faith.pay.payment.dto;

import com.faith.pay.payment.Payment;

public record PaymentResponse(

        String reference,

        Long orderId,

        long amountKobo,

        String currency,

        String status,

        long refundedKobo
) {

    public static PaymentResponse from(
            Payment payment
    ) {

        return new PaymentResponse(
                payment.getReference(),
                payment.getOrder().getId(),
                payment.getAmountKobo(),
                payment.getCurrency(),
                payment.getStatus().name(),
                payment.getRefundedKobo()
        );
    }
}