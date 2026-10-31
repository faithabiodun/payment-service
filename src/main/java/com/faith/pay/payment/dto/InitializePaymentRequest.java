package com.faith.pay.payment.dto;

import jakarta.validation.constraints.NotNull;
import com.faith.pay.provider.core.PaymentProviderType;

public record InitializePaymentRequest(

        @NotNull
        Long orderId,
        PaymentProviderType provider
) {
    public InitializePaymentRequest(Long orderId) { this(orderId, null); }
}
