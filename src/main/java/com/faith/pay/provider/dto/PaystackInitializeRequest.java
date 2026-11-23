package com.faith.pay.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PaystackInitializeRequest(
        String email,
        String amount,
        String reference,
        String currency,

        @JsonProperty("callback_url")
        String callbackUrl
) {
    public PaystackInitializeRequest(String email, String amount, String reference, String callbackUrl) {
        this(email, amount, reference, "NGN", callbackUrl);
    }
}
