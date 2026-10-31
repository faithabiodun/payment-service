package com.faith.pay.webhook;

import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.core.VerifiedRefund;
import com.faith.pay.refund.RefundStateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlutterwaveRefundWebhookProcessor {
    private final ProviderEventDeduplicator deduplicator;
    private final RefundStateService refunds;

    public FlutterwaveRefundWebhookProcessor(ProviderEventDeduplicator deduplicator, RefundStateService refunds) {
        this.deduplicator = deduplicator;
        this.refunds = refunds;
    }

    @Transactional
    public void apply(String event, String localRefundReference, VerifiedRefund verified, String rawPayload) {
        if (verified.providerRefundId() == null || verified.providerRefundId().isBlank()) {
            throw new IllegalArgumentException("Flutterwave refund verification returned no refund ID");
        }
        if (!deduplicator.claim("FLUTTERWAVE:" + event,
                verified.providerRefundId() + ":" + verified.state().name())) return;
        refunds.applyVerifiedState(localRefundReference, verified, PaymentEventSource.WEBHOOK, rawPayload);
    }
}
