package com.faith.pay.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PaystackInitializeData(

        @JsonProperty("authorization_url")
        String authorizationUrl,

        @JsonProperty("access_code")
        String accessCode,

        String reference
) {
}