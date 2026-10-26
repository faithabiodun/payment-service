package com.faith.pay.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigInteger;
import java.time.Instant;

public record PaystackRefundData(

        BigInteger id,

        String status,

        long amount,

        String currency,

        @JsonProperty("expected_at")
        Instant expectedAt,

        @JsonProperty("refunded_at")
        Instant refundedAt
) {
}
