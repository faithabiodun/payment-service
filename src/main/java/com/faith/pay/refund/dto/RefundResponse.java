package com.faith.pay.refund.dto;

import com.faith.pay.refund.Refund;

import java.time.Instant;

public record RefundResponse(
        Long id,
        String reference,
        String paymentReference,
        long amountKobo,
        String status,
        String providerStatus,
        String providerRefundId,
        String reason,
        Instant createdAt,
        Instant completedAt
) {
    public static RefundResponse from(Refund refund) {
        return new RefundResponse(
                refund.getId(),
                refund.getReference(),
                refund.getPayment().getReference(),
                refund.getAmountKobo(),
                refund.getStatus().name(),
                refund.getProviderStatus(),
                refund.getProviderRefundId(),
                refund.getReason(),
                refund.getCreatedAt(),
                refund.getCompletedAt()
        );
    }
}
