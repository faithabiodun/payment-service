package com.faith.pay.webhook;

import com.faith.pay.provider.dto.PaystackTransactionData;
import com.faith.pay.refund.RefundWebhookService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

@Service
public class WebhookService {

    private final ObjectMapper objectMapper;
    private final ChargeWebhookService charges;
    private final RefundWebhookService refunds;

    public WebhookService(
            ObjectMapper objectMapper,
            ChargeWebhookService charges,
            RefundWebhookService refunds
    ) {
        this.objectMapper = objectMapper;
        this.charges = charges;
        this.refunds = refunds;
    }

    /** Routes an authenticated webhook. Provider HTTP calls run outside transactions. */
    public void process(byte[] rawBody) {
        try {
            PaystackWebhook webhook = objectMapper.readValue(rawBody, PaystackWebhook.class);
            String rawPayload = new String(rawBody, StandardCharsets.UTF_8);

            if ("charge.success".equals(webhook.event())) {
                PaystackTransactionData transaction = objectMapper.treeToValue(
                        webhook.data(), PaystackTransactionData.class);
                charges.process(transaction, rawPayload);
                return;
            }

            if (isRefundEvent(webhook.event())) {
                PaystackRefundWebhookData refund = objectMapper.treeToValue(
                        webhook.data(), PaystackRefundWebhookData.class);
                refunds.process(webhook.event(), refund, rawPayload);
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not process Paystack webhook", failure);
        }
    }

    private boolean isRefundEvent(String event) {
        return "refund.pending".equals(event)
                || "refund.processing".equals(event)
                || "refund.needs-attention".equals(event)
                || "refund.failed".equals(event)
                || "refund.processed".equals(event);
    }
}
