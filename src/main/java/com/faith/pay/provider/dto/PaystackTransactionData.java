package com.faith.pay.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigInteger;
import java.time.Instant;

public record PaystackTransactionData(

        BigInteger id,

        String status,

        String reference,

        long amount,

        String currency,

        String channel,

        Long fees,

        @JsonProperty("paid_at")
        Instant paidAt
) {
}