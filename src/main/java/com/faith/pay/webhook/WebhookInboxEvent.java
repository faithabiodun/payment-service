package com.faith.pay.webhook;

import com.faith.pay.provider.core.PaymentProviderType;

public record WebhookInboxEvent(long id, PaymentProviderType provider, String providerEventId,
                                String eventType, String rawPayload, int attemptCount) {
}
