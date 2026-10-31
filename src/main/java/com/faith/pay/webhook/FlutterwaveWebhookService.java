package com.faith.pay.webhook;

import com.faith.pay.error.PaymentNotFoundException;
import com.faith.pay.payment.PaymentRepository;
import com.faith.pay.provider.core.RefundProviderRegistry;
import com.faith.pay.provider.core.VerifiedRefund;
import com.faith.pay.refund.RefundRepository;
import com.faith.pay.provider.core.PaymentProviderRegistry;
import com.faith.pay.provider.core.PaymentProviderType;
import com.faith.pay.provider.core.VerifiedPayment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/** Authenticated Flutterwave signal is verified against the API before it reaches settlement. */
@Service
public class FlutterwaveWebhookService {
    private final ObjectMapper mapper;
    private final PaymentProviderRegistry providers;
    private final PaymentRepository payments;
    private final FlutterwaveChargeWebhookProcessor processor;
    private final RefundProviderRegistry refundProviders;
    private final RefundRepository refunds;
    private final FlutterwaveRefundWebhookProcessor refundProcessor;

    public FlutterwaveWebhookService(ObjectMapper mapper, PaymentProviderRegistry providers,
                                     PaymentRepository payments, FlutterwaveChargeWebhookProcessor processor,
                                     RefundProviderRegistry refundProviders, RefundRepository refunds,
                                     FlutterwaveRefundWebhookProcessor refundProcessor) {
        this.mapper = mapper;
        this.providers = providers;
        this.payments = payments;
        this.processor = processor;
        this.refundProviders = refundProviders;
        this.refunds = refunds;
        this.refundProcessor = refundProcessor;
    }

    public void process(byte[] rawBody) {
        try {
            JsonNode root = mapper.readTree(rawBody);
            String event = value(root, "event");
            if (event == null) event = value(root, "event.type");
            JsonNode data = root.path("data");
            if (event != null && event.toLowerCase(java.util.Locale.ROOT).startsWith("refund")) {
                processRefund(event, data, rawBody);
                return;
            }
            if (!"charge.completed".equals(event)) return;

            String reference = value(data, "tx_ref");
            String transactionId = value(data, "id");
            if (reference == null || transactionId == null) {
                throw new IllegalArgumentException("Flutterwave charge webhook is missing tx_ref or data.id");
            }

            var payment = payments.findByReference(reference)
                    .orElseThrow(() -> new PaymentNotFoundException(reference));
            if (payment.getProvider() != PaymentProviderType.FLUTTERWAVE) {
                throw new IllegalArgumentException("Flutterwave event does not belong to a Flutterwave payment");
            }

            // Flutterwave recommends re-querying status, amount, currency and tx_ref before fulfillment.
            VerifiedPayment verified = providers.get(PaymentProviderType.FLUTTERWAVE).verify(reference, transactionId);
            if (!transactionId.equals(verified.providerPaymentId())) {
                throw new IllegalArgumentException("Flutterwave webhook transaction ID does not match verified transaction");
            }
            processor.apply(event, verified, new String(rawBody, StandardCharsets.UTF_8));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Could not parse Flutterwave webhook", failure);
        }
    }

    private void processRefund(String event, JsonNode data, byte[] rawBody) {
        String providerRefundId = value(data, "id");
        if (providerRefundId == null) throw new IllegalArgumentException("Flutterwave refund webhook is missing data.id");
        var local = refunds.findByProviderAndProviderRefundId(PaymentProviderType.FLUTTERWAVE, providerRefundId);
        if (local.isEmpty()) return;
        String localReference = local.get().getReference();
        String currency = local.get().getPayment().getCurrency();
        VerifiedRefund verified = refundProviders.get(PaymentProviderType.FLUTTERWAVE)
                .verifyRefund(providerRefundId, currency);
        refundProcessor.apply(event, localReference, verified, new String(rawBody, StandardCharsets.UTF_8));
    }

    private String value(JsonNode node, String field) {
        JsonNode value = node;
        for (String part : field.split("\\.")) value = value == null ? null : value.get(part);
        return value == null || value.isNull() ? null : value.asText();
    }
}
