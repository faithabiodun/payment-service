package com.faith.paymentservice;

import com.faith.pay.config.StripeProperties;
import com.faith.pay.payment.Payment;
import com.faith.pay.payment.PaymentRepository;
import com.faith.pay.provider.core.*;
import com.faith.pay.provider.stripe.StripeWebhookVerifier;
import com.faith.pay.refund.RefundRepository;
import com.faith.pay.webhook.StripePaymentWebhookProcessor;
import com.faith.pay.webhook.StripeRefundWebhookProcessor;
import com.faith.pay.webhook.StripeWebhookController;
import com.faith.pay.webhook.StripeWebhookService;
import com.faith.pay.webhook.WebhookInboxService;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StripeWebhookServiceTests {
    private static final String SECRET = "whsec_unit_test_secret";

    @Test
    void controllerRejectsInvalidStripeSignatureBeforeWebhookProcessing() {
        var inbox = mock(WebhookInboxService.class);
        var controller = new StripeWebhookController(new StripeWebhookVerifier(properties()), inbox);

        var response = controller.receive("{}".getBytes(StandardCharsets.UTF_8), "t=1,v1=invalid");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(inbox);
    }

    @Test
    void validCheckoutSessionWebhookRechecksStripeThenCallsSharedSettlementProcessor() throws Exception {
        var paymentRegistry = mock(PaymentProviderRegistry.class);
        var provider = mock(PaymentProvider.class);
        var paymentRepository = mock(PaymentRepository.class);
        var paymentProcessor = mock(StripePaymentWebhookProcessor.class);
        var refundRegistry = mock(RefundProviderRegistry.class);
        var refundRepository = mock(RefundRepository.class);
        var refundProcessor = mock(StripeRefundWebhookProcessor.class);
        var payment = mock(Payment.class);
        var service = new StripeWebhookService(paymentRegistry, refundRegistry, paymentRepository, refundRepository,
                paymentProcessor, refundProcessor);

        String raw = """
                {"id":"evt_test_checkout_1","object":"event","api_version":"2026-09-30.endive",
                 "created":1793455200,"livemode":false,"type":"checkout.session.completed",
                 "data":{"object":{"id":"cs_test_123","object":"checkout.session","client_reference_id":"PAY-STRIPE-1",
                   "mode":"payment","status":"complete","payment_status":"paid","payment_intent":"pi_test_123"}}}
                """;
        Event event = new StripeWebhookVerifier(properties()).verify(raw.getBytes(StandardCharsets.UTF_8), signature(raw));
        var verified = new VerifiedPayment(PaymentProviderType.STRIPE, "pi_test_123", "PAY-STRIPE-1",
                ProviderPaymentState.SUCCEEDED, 500_000, "ngn", "card", null, Instant.now());

        when(paymentRepository.findByReference("PAY-STRIPE-1")).thenReturn(Optional.of(payment));
        when(payment.getProvider()).thenReturn(PaymentProviderType.STRIPE);
        when(paymentRegistry.get(PaymentProviderType.STRIPE)).thenReturn(provider);
        when(provider.verify("PAY-STRIPE-1", "cs_test_123")).thenReturn(verified);

        service.process(event, raw.getBytes(StandardCharsets.UTF_8));

        verify(provider).verify("PAY-STRIPE-1", "cs_test_123");
        verify(paymentProcessor).apply("evt_test_checkout_1", "checkout.session.completed", verified, raw);
        verifyNoInteractions(refundProcessor);
    }

    private StripeProperties properties() {
        return new StripeProperties("sk_test_not_used", SECRET, "https://api.stripe.com",
                "http://localhost/callback", "http://localhost/callback");
    }

    private String signature(String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + java.util.HexFormat.of().formatHex(digest);
    }
}
