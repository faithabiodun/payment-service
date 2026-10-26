package com.faith.pay.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

public record PaystackRefundWebhookData(
        String status,

        @JsonProperty("transaction_reference")
        String transactionReference,

        @JsonProperty("refund_reference")
        String refundReference,

        JsonNode amount,

        String currency
) {
}
