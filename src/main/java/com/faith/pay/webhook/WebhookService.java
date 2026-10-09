package com.faith.pay.webhook;

import tools.jackson.databind.ObjectMapper;

import com.faith.pay.payment.PaymentCompletionService;
import com.faith.pay.provider.dto.PaystackTransactionData;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookService {

    private final ObjectMapper objectMapper;
    private final ProviderEventDeduplicator deduplicator;
    private final PaymentCompletionService completionService;

    public WebhookService(
            ObjectMapper objectMapper,
            ProviderEventDeduplicator deduplicator,
            PaymentCompletionService completionService
    ) {
        this.objectMapper =
                objectMapper;

        this.deduplicator =
                deduplicator;

        this.completionService =
                completionService;
    }


    @Transactional
    public void process(
            byte[] rawBody
    ) {

        try {

            PaystackWebhook webhook =
                    objectMapper.readValue(
                            rawBody,
                            PaystackWebhook.class
                    );


            if (!"charge.success"
                    .equals(webhook.event())) {

                return;
            }


            PaystackTransactionData data =
                    objectMapper.treeToValue(
                            webhook.data(),
                            PaystackTransactionData.class
                    );


            /*
             * Paystack's charge data contains
             * the transaction id in data.id.
             */
            String providerId =
                    data.id().toString();


            boolean firstTime =
                    deduplicator.claim(
                            webhook.event(),
                            providerId
                    );


            if (!firstTime) {

                // Duplicate delivery.
                // That's normal.
                return;
            }


            String rawJson =
                    new String(
                            rawBody,
                            java.nio.charset
                                    .StandardCharsets.UTF_8
                    );


            completionService.applySuccess(
                    data,
                    "WEBHOOK",
                    rawJson
            );

        } catch (Exception e) {

            throw new IllegalStateException(
                    "Could not process webhook",
                    e
            );
        }
    }
}