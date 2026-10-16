package com.faith.pay.payment.dto;

import com.faith.pay.payment.Payment;

import java.time.Instant;

public record PaymentResponse(

        Long id,

        String reference,

        Long orderId,

        long amountKobo,

        String currency,

        String status,

        String providerTransactionId,

        String channel,

        Long feesKobo,

        long refundedKobo,

        Instant paidAt,

        Instant createdAt,

        Instant updatedAt
) {

    public static PaymentResponse from(
            Payment payment
    ) {

        return new PaymentResponse(
                payment.getId(),

                payment.getReference(),

                payment
                        .getOrder()
                        .getId(),

                payment.getAmountKobo(),

                payment.getCurrency(),

                payment
                        .getStatus()
                        .name(),

                payment
                        .getProviderTransactionId(),

                payment.getChannel(),

                payment.getFeesKobo(),

                payment.getRefundedKobo(),

                payment.getPaidAt(),

                payment.getCreatedAt(),

                payment.getUpdatedAt()
        );
    }
}