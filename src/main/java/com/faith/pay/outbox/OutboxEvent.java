package com.faith.pay.outbox;

public record OutboxEvent(long id, String aggregateType, String aggregateId, String eventType,
                          String payload, int attemptCount) {
}
