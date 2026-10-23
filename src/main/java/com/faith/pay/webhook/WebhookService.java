package com.faith.pay.webhook;

import tools.jackson.databind.ObjectMapper;

import com.faith.pay.payment.*;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.dto.PaystackTransactionData;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
public class WebhookService {

    private static final String CHARGE_SUCCESS = "charge.success";

    private final ObjectMapper objectMapper;
    private final ProviderEventDeduplicator deduplicator;
    private final PaymentSettlementService settlement;

    public WebhookService(
            ObjectMapper objectMapper,
            ProviderEventDeduplicator deduplicator,
            PaymentSettlementService settlement
    ) {
        this.objectMapper = objectMapper;
        this.deduplicator = deduplicator;
        this.settlement = settlement;
    }

    @Transactional
    public void process(byte[] rawBody) {
        try {
            /*
             * Signature verification has already happened in the controller.
             * Only NOW do we deserialize.
             */
            PaystackWebhook webhook = objectMapper.readValue(
                    rawBody,
                    PaystackWebhook.class
            );

            /*
             * We currently care only about successful charges.
             * Refund events come in Checkpoint 5.
             */
            if (!CHARGE_SUCCESS.equals(webhook.event())) {
                return;
            }

            PaystackTransactionData transaction = objectMapper.treeToValue(
                    webhook.data(),
                    PaystackTransactionData.class
            );

            if (transaction.id() == null) {
                throw new IllegalStateException(
                        "charge.success contained no transaction id"
                );
            }

            String providerTransactionId = transaction.id().toString();

            /*
             * Atomically claim this provider event.
             */
            boolean firstProcessing = deduplicator.claim(
                    webhook.event(),
                    providerTransactionId
            );

            if (!firstProcessing) {
                // Duplicate delivery. This is normal.
                return;
            }

            String rawJson = new String(rawBody, StandardCharsets.UTF_8);

            /*
             * SAME settlement service used by manual verification.
             */
            settlement.settleSuccess(
                    transaction.reference(),
                    transaction,
                    PaymentEventSource.WEBHOOK,
                    rawJson
            );
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not process Paystack webhook",
                    e
            );
        }
    }
}
