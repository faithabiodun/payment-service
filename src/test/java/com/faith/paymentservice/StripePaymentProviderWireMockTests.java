package com.faith.paymentservice;

import com.faith.pay.config.StripeProperties;
import com.faith.pay.provider.core.*;
import com.faith.pay.provider.stripe.StripePaymentProvider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.stripe.StripeClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class StripePaymentProviderWireMockTests {
    private static final WireMockServer stripeApi = new WireMockServer(options().dynamicPort());
    static { stripeApi.start(); }

    private StripePaymentProvider provider;

    @BeforeEach
    void setUp() {
        stripeApi.resetAll();
        StripeClient client = StripeClient.builder().setApiKey("sk_test_wiremock")
                .setApiBase(stripeApi.baseUrl()).setMaxNetworkRetries(0).build();
        provider = new StripePaymentProvider(client, new StripeProperties("sk_test_wiremock", "whsec_test",
                stripeApi.baseUrl(), "http://localhost:8080/api/v1/payments/callback",
                "http://localhost:8080/api/v1/payments/callback"));
    }

    @AfterAll
    static void stop() { stripeApi.stop(); }

    @Test
    void createsHostedCheckoutUsingDatabaseAmountAndLocalIdempotencyKey() {
        stripeApi.stubFor(post(urlEqualTo("/v1/checkout/sessions"))
                .withHeader("Authorization", equalTo("Bearer sk_test_wiremock"))
                .withHeader("Idempotency-Key", equalTo("operation-checkout-1"))
                .willReturn(okJson("""
                        {"id":"cs_test_123","object":"checkout.session","url":"https://checkout.stripe.test/cs_test_123",
                         "client_reference_id":"PAY-STRIPE-1","mode":"payment","status":"open","payment_status":"unpaid"}
                        """)));

        var result = provider.initialize(new ProviderPaymentRequest("PAY-STRIPE-1", "buyer@example.test",
                500_000, "NGN", null, "operation-checkout-1"));

        assertThat(result.provider()).isEqualTo(PaymentProviderType.STRIPE);
        assertThat(result.providerSessionId()).isEqualTo("cs_test_123");
        assertThat(result.state()).isEqualTo(ProviderPaymentState.REQUIRES_ACTION);
        assertThat(result.checkoutUrl()).isEqualTo("https://checkout.stripe.test/cs_test_123");
        String body = URLDecoder.decode(new String(stripeApi.getAllServeEvents().getFirst().getRequest().getBody(),
                StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThat(body).contains("client_reference_id=PAY-STRIPE-1", "price_data][unit_amount]=500000",
                "price_data][currency]=ngn", "metadata[payment_reference]=PAY-STRIPE-1");
        stripeApi.verify(1, postRequestedFor(urlEqualTo("/v1/checkout/sessions")));
    }

    @Test
    void verifiesCheckoutSessionByRetrievingItsPaymentIntentAndMappingSuccess() {
        stripeApi.stubFor(get(urlPathEqualTo("/v1/checkout/sessions/cs_test_123"))
                .willReturn(okJson("""
                        {"id":"cs_test_123","object":"checkout.session","client_reference_id":"PAY-STRIPE-2",
                         "mode":"payment","status":"complete","payment_status":"paid","currency":"ngn",
                         "amount_total":500000,"payment_intent":{"id":"pi_test_123","object":"payment_intent",
                           "status":"succeeded","amount":500000,"amount_received":500000,"currency":"ngn",
                           "payment_method_types":["card"],"created":1793455200,
                           "metadata":{"payment_reference":"PAY-STRIPE-2"}}}
                        """)));

        VerifiedPayment result = provider.verify("PAY-STRIPE-2", "cs_test_123");

        assertThat(result.providerPaymentId()).isEqualTo("pi_test_123");
        assertThat(result.reference()).isEqualTo("PAY-STRIPE-2");
        assertThat(result.amountMinor()).isEqualTo(500_000);
        assertThat(result.currency()).isEqualTo("ngn");
        assertThat(result.state()).isEqualTo(ProviderPaymentState.SUCCEEDED);
    }

    @Test
    void createsRefundWithItsOwnStableProviderIdempotencyKey() {
        stripeApi.stubFor(post(urlEqualTo("/v1/refunds"))
                .withHeader("Idempotency-Key", equalTo("operation-refund-1"))
                .willReturn(okJson("""
                        {"id":"re_test_123","object":"refund","amount":125000,"currency":"ngn",
                         "status":"pending","payment_intent":"pi_test_123",
                         "metadata":{"local_refund_reference":"REF-STRIPE-1","payment_reference":"PAY-STRIPE-3"}}
                        """)));

        ProviderRefundResult result = provider.refund(new ProviderRefundRequest("PAY-STRIPE-3", "pi_test_123",
                "REF-STRIPE-1", 125_000, "NGN", "test refund", "operation-refund-1"));

        assertThat(result.providerRefundId()).isEqualTo("re_test_123");
        assertThat(result.state()).isEqualTo(ProviderRefundState.PENDING);
        assertThat(result.amountMinor()).isEqualTo(125_000);
        String body = URLDecoder.decode(new String(stripeApi.getAllServeEvents().getFirst().getRequest().getBody(),
                StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThat(body).contains("payment_intent=pi_test_123", "amount=125000",
                "metadata[local_refund_reference]=REF-STRIPE-1");
    }

    @Test
    void retrievesRefundAndFindsOneByPaymentIntentAndLocalMarker() {
        stripeApi.stubFor(get(urlEqualTo("/v1/refunds/re_test_123"))
                .willReturn(okJson("""
                        {"id":"re_test_123","object":"refund","amount":125000,"currency":"ngn","status":"succeeded"}
                        """)));
        var verified = provider.verifyRefund("re_test_123", "NGN");
        assertThat(verified.state()).isEqualTo(ProviderRefundState.SUCCEEDED);
        assertThat(verified.amountMinor()).isEqualTo(125_000);

        stripeApi.resetAll();
        stripeApi.stubFor(get(urlPathEqualTo("/v1/refunds"))
                .withQueryParam("payment_intent", equalTo("pi_test_123"))
                .willReturn(okJson("""
                        {"object":"list","data":[{"id":"re_test_123","object":"refund","amount":125000,
                          "currency":"ngn","status":"pending","metadata":{"local_refund_reference":"REF-STRIPE-1"}}],
                         "has_more":false,"url":"/v1/refunds"}
                        """)));
        assertThat(provider.findRefundsForPayment("pi_test_123", "REF-STRIPE-1", "NGN"))
                .extracting(VerifiedRefund::providerRefundId).containsExactly("re_test_123");
    }
}
