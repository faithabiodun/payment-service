package com.faith.pay.webhook;

import com.faith.pay.provider.core.PaymentProviderType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
public class WebhookInboxService {
    private final ProviderWebhookEnvelopeParser parser;
    private final WebhookInboxRepository inbox;

    public WebhookInboxService(ProviderWebhookEnvelopeParser parser, WebhookInboxRepository inbox) {
        this.parser = parser;
        this.inbox = inbox;
    }

    /** A webhook is safe to acknowledge after this insert transaction commits. */
    @Transactional
    public boolean accept(PaymentProviderType provider, byte[] rawBody, String stripeEventId, String stripeEventType) {
        var receipt = parser.parse(provider, rawBody, stripeEventId, stripeEventType);
        return inbox.insert(provider, receipt.providerEventId(), receipt.deduplicationKey(), receipt.eventType(),
                new String(rawBody, StandardCharsets.UTF_8));
    }
}
