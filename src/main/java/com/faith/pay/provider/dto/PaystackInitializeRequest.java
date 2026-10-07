package com.faith.pay.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PaystackInitializeRequest(
        String email,
        String amount,
        String reference,

        @JsonProperty("callback_url")
        String callbackUrl
) {
}
