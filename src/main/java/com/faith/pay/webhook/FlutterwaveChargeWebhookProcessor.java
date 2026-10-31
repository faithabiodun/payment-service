package com.faith.pay.webhook;

import com.faith.pay.payment.PaymentSettlementService;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.core.VerifiedPayment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps event claiming and the financial effect in one database transaction. */
@Service
public class FlutterwaveChargeWebhookProcessor {
    private final ProviderEventDeduplicator deduplicator;
    private final PaymentSettlementService settlement;

    public FlutterwaveChargeWebhookProcessor(ProviderEventDeduplicator deduplicator,
                                             PaymentSettlementService settlement) {
        this.deduplicator = deduplicator;
        this.settlement = settlement;
    }

    @Transactional
    public void apply(String event, VerifiedPayment verified, String rawPayload) {
        if (verified.providerPaymentId() == null || verified.providerPaymentId().isBlank()) {
            throw new IllegalArgumentException("Flutterwave verification returned no transaction ID");
        }
        if (!deduplicator.claim("FLUTTERWAVE:" + event, verified.providerPaymentId())) return;
        if (verified.succeeded()) {
            settlement.settleSuccess(verified.reference(), verified, PaymentEventSource.WEBHOOK, rawPayload);
        }
    }
}
