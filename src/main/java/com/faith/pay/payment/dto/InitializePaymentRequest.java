package com.faith.pay.payment.dto;

import jakarta.validation.constraints.NotNull;

public record InitializePaymentRequest(

        @NotNull
        Long orderId
) {
}