package com.faith.pay.order.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(

        @NotBlank
        @Email
        String email,

        @Positive
        long amountKobo
) {
}
