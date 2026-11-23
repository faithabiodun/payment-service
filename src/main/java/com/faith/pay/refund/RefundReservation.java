package com.faith.pay.refund;

import com.faith.pay.provider.core.PaymentProviderType;

public record RefundReservation(
        Long refundId,
        String refundReference,
        String paymentReference,
        PaymentProviderType provider,
        String providerTransactionId,
        String currency,
        long amountKobo,
        RefundStatus status,
        String providerRefundId,
        boolean newlyCreated
) {
    public RefundReservation(
            Long refundId,
            String refundReference,
            String paymentReference,
            long amountKobo,
            RefundStatus status,
            String providerRefundId,
            boolean newlyCreated
    ) {
        this(refundId, refundReference, paymentReference, PaymentProviderType.PAYSTACK, null, "NGN",
                amountKobo, status, providerRefundId, newlyCreated);
    }
}
