package com.faith.pay.order.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(

        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @Positive
        long amountKobo
) {
}
