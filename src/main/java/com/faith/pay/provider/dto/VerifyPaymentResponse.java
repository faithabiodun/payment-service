package com.faith.pay.provider.dto;

import com.faith.pay.payment.dto.PaymentResponse;

public record VerifyPaymentResponse(
        String providerStatus,
        boolean settlementApplied,
        PaymentResponse payment
) {
}
