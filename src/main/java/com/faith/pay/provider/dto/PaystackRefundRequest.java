package com.faith.pay.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PaystackRefundRequest(

        String transaction,

        Long amount,

        @JsonProperty("merchant_note")
        String merchantNote
) {
}