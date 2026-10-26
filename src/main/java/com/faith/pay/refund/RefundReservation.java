package com.faith.pay.refund;

public record RefundReservation(
        Long refundId,
        String refundReference,
        String paymentReference,
        long amountKobo,
        RefundStatus status,
        String providerRefundId,
        boolean newlyCreated
) {
}
