package com.faith.pay.webhook;

import com.faith.pay.payment.PaymentSettlementService;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.core.VerifiedPayment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StripePaymentWebhookProcessor {
    private final ProviderEventDeduplicator deduplicator;
    private final PaymentSettlementService settlement;

    public StripePaymentWebhookProcessor(ProviderEventDeduplicator deduplicator,
                                         PaymentSettlementService settlement) {
        this.deduplicator = deduplicator;
        this.settlement = settlement;
    }

    @Transactional
    public void apply(String eventId, String eventType, VerifiedPayment verified, String rawPayload) {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("Stripe event ID is required");
        if (!deduplicator.claim("STRIPE:" + eventType, eventId)) return;
        if (verified.succeeded()) {
            settlement.settleSuccess(verified.reference(), verified, PaymentEventSource.WEBHOOK, rawPayload);
        }
    }
}
