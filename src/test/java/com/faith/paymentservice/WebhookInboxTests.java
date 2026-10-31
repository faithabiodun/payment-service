package com.faith.paymentservice;

import com.faith.pay.provider.core.PaymentProviderType;
import com.faith.pay.webhook.ProviderWebhookEnvelopeParser;
import com.faith.pay.webhook.WebhookInboxRepository;
import com.faith.pay.webhook.WebhookInboxService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WebhookInboxTests {
    @Test
    void stripeSdkCanRehydrateTheDurableEventPayload() {
        String raw = """
                {"id":"evt_durable","object":"event","api_version":"2026-09-30.endive",
                 "created":1793455200,"livemode":false,"type":"checkout.session.completed",
                 "data":{"object":{"id":"cs_test_123","object":"checkout.session",
                 "client_reference_id":"PAY-1","mode":"payment","status":"complete",
                 "payment_status":"paid","payment_intent":"pi_test_123"}}}
                """;

        var event = com.stripe.net.ApiResource.GSON.fromJson(raw, com.stripe.model.Event.class);
        var object = event.getDataObjectDeserializer().getObject().orElse(event.getData().getObject());

        assertThat(event.getId()).isEqualTo("evt_durable");
        assertThat(object).isInstanceOf(com.stripe.model.checkout.Session.class);
    }

    @Test
    void flutterwaveUsesDocumentedEventObjectAndStatusAsDeduplicationMaterial() {
        var parser = new ProviderWebhookEnvelopeParser(new ObjectMapper());
        byte[] payload = "{\"event\":\"charge.completed\",\"data\":{\"id\":123,\"status\":\"successful\"}}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        var first = parser.parse(PaymentProviderType.FLUTTERWAVE, payload, null, null);
        var duplicate = parser.parse(PaymentProviderType.FLUTTERWAVE, payload, null, null);

        assertThat(first.providerEventId()).isNull();
        assertThat(first.eventType()).isEqualTo("charge.completed");
        assertThat(first.deduplicationKey()).isEqualTo(duplicate.deduplicationKey());
    }

    @Test
    void stripeInboxKeepsTheActualProviderEventId() {
        var parser = new ProviderWebhookEnvelopeParser(new ObjectMapper());

        var receipt = parser.parse(PaymentProviderType.STRIPE, "{}".getBytes(), "evt_123", "refund.updated");

        assertThat(receipt.providerEventId()).isEqualTo("evt_123");
        assertThat(receipt.eventType()).isEqualTo("refund.updated");
    }

    @Test
    void authenticatedReceiptIsStoredAndRepositoryDuplicateResultIsReturned() {
        var repository = mock(WebhookInboxRepository.class);
        var inbox = new WebhookInboxService(new ProviderWebhookEnvelopeParser(new ObjectMapper()), repository);
        byte[] payload = "{\"event\":\"charge.completed\",\"data\":{\"id\":123}}".getBytes();
        when(repository.insert(eq(PaymentProviderType.PAYSTACK), isNull(), anyString(), eq("charge.completed"), anyString()))
                .thenReturn(false);

        boolean inserted = inbox.accept(PaymentProviderType.PAYSTACK, payload, null, null);

        assertThat(inserted).isFalse();
        verify(repository).insert(eq(PaymentProviderType.PAYSTACK), isNull(), anyString(),
                eq("charge.completed"), eq(new String(payload, java.nio.charset.StandardCharsets.UTF_8)));
    }
}
