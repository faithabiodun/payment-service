package com.faith.pay.webhook;

import com.faith.pay.provider.core.PaymentProviderType;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class WebhookInboxWorker {
    private static final Logger log = LoggerFactory.getLogger(WebhookInboxWorker.class);
    private static final int MAX_ATTEMPTS = 8;

    private final WebhookInboxRepository inbox;
    private final WebhookService paystack;
    private final FlutterwaveWebhookService flutterwave;
    private final StripeWebhookService stripe;
    private final int batchSize;
    private final int leaseSeconds;

    public WebhookInboxWorker(WebhookInboxRepository inbox, WebhookService paystack,
                              FlutterwaveWebhookService flutterwave, StripeWebhookService stripe,
                              @Value("${webhook.inbox.batch-size:25}") int batchSize,
                              @Value("${webhook.inbox.lease-seconds:120}") int leaseSeconds) {
        this.inbox = inbox;
        this.paystack = paystack;
        this.flutterwave = flutterwave;
        this.stripe = stripe;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(fixedDelayString = "${webhook.inbox.poll-millis:3000}")
    public void processBatch() {
        for (WebhookInboxEvent event : inbox.claimBatch(batchSize, leaseSeconds, MAX_ATTEMPTS)) process(event);
    }

    private void process(WebhookInboxEvent event) {
        try {
            switch (event.provider()) {
                case PAYSTACK -> paystack.process(event.rawPayload().getBytes(StandardCharsets.UTF_8));
                case FLUTTERWAVE -> flutterwave.process(event.rawPayload().getBytes(StandardCharsets.UTF_8));
                case STRIPE -> stripe.processTrustedEvent(
                        ApiResource.GSON.fromJson(event.rawPayload(), Event.class),
                        event.rawPayload().getBytes(StandardCharsets.UTF_8));
            }
            markProcessed(event.id(), event.attemptCount());
        } catch (RuntimeException failure) {
            long base = Math.min(3600L, 1L << Math.min(event.attemptCount(), 10));
            long delay = base + ThreadLocalRandom.current().nextLong(Math.max(1L, base / 2L));
            markFailed(event, event.attemptCount(), failure.getClass().getSimpleName(), delay);
            log.warn("Webhook inbox processing deferred; provider={}, inboxId={}, attempt={}, errorType={}",
                    event.provider(), event.id(), event.attemptCount(), failure.getClass().getSimpleName());
        }
    }

    protected void markProcessed(long id, int attempts) { inbox.markProcessed(id, attempts); }

    protected void markFailed(WebhookInboxEvent event, int attempts, String safeError, long delay) {
        inbox.markFailed(event.id(), attempts, safeError, delay, MAX_ATTEMPTS);
    }
}
