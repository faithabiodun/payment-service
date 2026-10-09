package com.faith.pay.webhook;

import tools.jackson.databind.JsonNode;

public record PaystackWebhook(

        String event,

        JsonNode data
) {
}