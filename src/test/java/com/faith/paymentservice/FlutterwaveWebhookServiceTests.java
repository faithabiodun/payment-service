package com.faith.paymentservice;

import com.faith.pay.payment.Payment;
import com.faith.pay.payment.PaymentRepository;
import com.faith.pay.payment.PaymentSettlementService;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.core.PaymentProviderRegistry;
import com.faith.pay.provider.core.RefundProviderRegistry;
import com.faith.pay.provider.core.PaymentProviderType;
import com.faith.pay.provider.core.PaymentProvider;
import com.faith.pay.provider.core.ProviderPaymentState;
import com.faith.pay.provider.core.VerifiedPayment;
import com.faith.pay.provider.core.VerifiedRefund;
import com.faith.pay.provider.core.ProviderRefundState;
import com.faith.pay.refund.Refund;
import com.faith.pay.refund.RefundRepository;
import com.faith.pay.webhook.FlutterwaveChargeWebhookProcessor;
import com.faith.pay.webhook.FlutterwaveRefundWebhookProcessor;
import com.faith.pay.webhook.FlutterwaveWebhookService;
import com.faith.pay.webhook.ProviderEventDeduplicator;
import com.faith.pay.webhook.FlutterwaveWebhookController;
import com.faith.pay.provider.flutterwave.FlutterwaveV3WebhookVerifier;
import com.faith.pay.config.FlutterwaveV3Properties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FlutterwaveWebhookServiceTests {
    @Test
    void controllerRejectsInvalidV3HashBeforeWebhookServiceRuns() {
        var service = mock(FlutterwaveWebhookService.class);
        var verifier = new FlutterwaveV3WebhookVerifier(new FlutterwaveV3Properties(
                "https://api.flutterwave.com/v3", "public", "secret", "encryption", "dashboard-hash", "http://localhost"));
        var controller = new FlutterwaveWebhookController(verifier, service);

        var response = controller.receive("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), "wrong-hash");

        org.assertj.core.api.Assertions.assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(service);
    }

    @Test
    void rechecksProviderTransactionBeforeSendingWebhookToSharedSettlement() {
        var mapper = new ObjectMapper();
        var registry = mock(PaymentProviderRegistry.class);
        var provider = mock(PaymentProvider.class);
        var payments = mock(PaymentRepository.class);
        var processor = mock(FlutterwaveChargeWebhookProcessor.class);
        var refundProviders = mock(RefundProviderRegistry.class);
        var refundRepository = mock(RefundRepository.class);
        var refundProcessor = mock(FlutterwaveRefundWebhookProcessor.class);
        var payment = mock(Payment.class);
        var service = new FlutterwaveWebhookService(mapper, registry, payments, processor,
                refundProviders, refundRepository, refundProcessor);
        String raw = """
                {"event":"charge.completed","data":{"id":12345,"tx_ref":"PAY-FLW-1","status":"successful"}}
                """;
        var verified = new VerifiedPayment(PaymentProviderType.FLUTTERWAVE, "12345", "PAY-FLW-1",
                ProviderPaymentState.SUCCEEDED, 500_000, "NGN", "card", 7_500L, null);

        when(payments.findByReference("PAY-FLW-1")).thenReturn(Optional.of(payment));
        when(payment.getProvider()).thenReturn(PaymentProviderType.FLUTTERWAVE);
        when(registry.get(PaymentProviderType.FLUTTERWAVE)).thenReturn(provider);
        when(provider.verify("PAY-FLW-1", "12345")).thenReturn(verified);

        service.process(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        verify(provider).verify("PAY-FLW-1", "12345");
        verify(processor).apply(eq("charge.completed"), eq(verified), contains("PAY-FLW-1"));
        verifyNoMoreInteractions(processor);
    }

    @Test
    void refundWebhookUsesIdOnlyAsHintThenFetchesCurrentRefundState() {
        var registry = mock(PaymentProviderRegistry.class);
        var provider = mock(PaymentProvider.class);
        var payments = mock(PaymentRepository.class);
        var processor = mock(FlutterwaveChargeWebhookProcessor.class);
        var refundProviders = mock(RefundProviderRegistry.class);
        var refundProvider = mock(com.faith.pay.provider.core.RefundProvider.class);
        var refundRepository = mock(RefundRepository.class);
        var refundProcessor = mock(FlutterwaveRefundWebhookProcessor.class);
        var refund = mock(Refund.class);
        var payment = mock(Payment.class);
        var service = new FlutterwaveWebhookService(new ObjectMapper(), registry, payments, processor,
                refundProviders, refundRepository, refundProcessor);
        var verified = new VerifiedRefund(PaymentProviderType.FLUTTERWAVE, "778899", null,
                ProviderRefundState.PROCESSING, 250_000, "NGN", "completed");

        when(refundRepository.findByProviderAndProviderRefundId(PaymentProviderType.FLUTTERWAVE, "778899"))
                .thenReturn(Optional.of(refund));
        when(refund.getReference()).thenReturn("REF-FLW-1");
        when(refund.getPayment()).thenReturn(payment);
        when(payment.getCurrency()).thenReturn("NGN");
        when(refundProviders.get(PaymentProviderType.FLUTTERWAVE)).thenReturn(refundProvider);
        when(refundProvider.verifyRefund("778899", "NGN")).thenReturn(verified);

        service.process("""
                {"event":"refund.completed","data":{"id":778899,"status":"completed"}}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        verify(refundProvider).verifyRefund("778899", "NGN");
        verify(refundProcessor).apply(eq("refund.completed"), eq("REF-FLW-1"), eq(verified), anyString());
        verifyNoInteractions(processor);
    }

    @Test
    void processorClaimsEventAndRunsSettlementOnlyOnce() {
        var deduplicator = mock(ProviderEventDeduplicator.class);
        var settlement = mock(PaymentSettlementService.class);
        var processor = new FlutterwaveChargeWebhookProcessor(deduplicator, settlement);
        var verified = new VerifiedPayment(PaymentProviderType.FLUTTERWAVE, "12345", "PAY-FLW-1",
                ProviderPaymentState.SUCCEEDED, 500_000, "NGN", "card", 7_500L, null);
        when(deduplicator.claim("FLUTTERWAVE:charge.completed", "12345")).thenReturn(true, false);

        processor.apply("charge.completed", verified, "{}");
        processor.apply("charge.completed", verified, "{}");

        verify(settlement, times(1)).settleSuccess("PAY-FLW-1", verified, PaymentEventSource.WEBHOOK, "{}");
    }
}
