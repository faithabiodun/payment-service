package com.faith.pay.webhook;

import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.core.VerifiedRefund;
import com.faith.pay.refund.RefundStateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StripeRefundWebhookProcessor {
    private final ProviderEventDeduplicator deduplicator;
    private final RefundStateService refunds;

    public StripeRefundWebhookProcessor(ProviderEventDeduplicator deduplicator, RefundStateService refunds) {
        this.deduplicator = deduplicator;
        this.refunds = refunds;
    }

    @Transactional
    public void apply(String eventId, String eventType, String localRefundReference,
                      VerifiedRefund verified, String rawPayload) {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("Stripe event ID is required");
        if (!deduplicator.claim("STRIPE:" + eventType, eventId)) return;
        refunds.applyVerifiedState(localRefundReference, verified, PaymentEventSource.WEBHOOK, rawPayload);
    }
}
