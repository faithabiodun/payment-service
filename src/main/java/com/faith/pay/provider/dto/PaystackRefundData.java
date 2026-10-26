package com.faith.pay.provider.dto;

import java.math.BigInteger;
import java.time.Instant;

public record PaystackRefundData(

        BigInteger id,

        String status,

        long amount,

        String currency,

        @com.fasterxml.jackson.annotation.JsonProperty("expected_at")
        Instant expectedAt,

        @com.fasterxml.jackson.annotation.JsonProperty("refunded_at")
        Instant refundedAt
) {
}
