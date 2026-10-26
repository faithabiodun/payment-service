package com.faith.pay.webhook;

import com.faith.pay.payment.PaymentSettlementService;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.dto.PaystackTransactionData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChargeWebhookService {

    private final ProviderEventDeduplicator deduplicator;
    private final PaymentSettlementService settlement;

    public ChargeWebhookService(
            ProviderEventDeduplicator deduplicator,
            PaymentSettlementService settlement
    ) {
        this.deduplicator = deduplicator;
        this.settlement = settlement;
    }

    @Transactional
    public void process(PaystackTransactionData transaction, String rawPayload) {
        if (transaction.id() == null) {
            throw new IllegalStateException("charge.success contained no transaction id");
        }

        boolean firstProcessing = deduplicator.claim(
                "charge.success",
                transaction.id().toString()
        );
        if (!firstProcessing) {
            return;
        }

        settlement.settleSuccess(
                transaction.reference(),
                transaction,
                PaymentEventSource.WEBHOOK,
                rawPayload
        );
    }
}
