package com.faith.paymentservice;

import com.faith.pay.config.FlutterwaveV3Properties;
import com.faith.pay.provider.core.ProviderPaymentRequest;
import com.faith.pay.provider.core.ProviderRefundRequest;
import com.faith.pay.provider.core.ProviderPaymentState;
import com.faith.pay.provider.core.ProviderRefundState;
import com.faith.pay.provider.flutterwave.FlutterwaveV3PaymentProvider;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class FlutterwaveV3ProviderWireMockTests {
    private static final WireMockServer provider = new WireMockServer(options().dynamicPort());
    static { provider.start(); }

    private FlutterwaveV3PaymentProvider adapter;

    @BeforeEach
    void setUp() {
        provider.resetAll();
        adapter = new FlutterwaveV3PaymentProvider(new FlutterwaveV3Properties(
                provider.baseUrl(), "public-test-key", "secret-test-key", "encryption-test-key",
                "webhook-hash", "http://localhost/callback"));
    }

    @AfterAll
    static void stop() { provider.stop(); }

    @Test
    void createsHostedPaymentUsingMinorUnitsConvertedToCurrencyMajorUnits() {
        provider.stubFor(post(urlEqualTo("/payments"))
                .withHeader("Authorization", equalTo("Bearer secret-test-key"))
                .willReturn(okJson("""
                        {"status":"success","data":{"link":"https://checkout.flutterwave.test/session/1"}}
                        """)));

        var result = adapter.initialize(new ProviderPaymentRequest("PAY-FLW-1", "buyer@example.test",
                500_000, "NGN", null, "local-operation-1"));

        assertThat(result.state()).isEqualTo(ProviderPaymentState.REQUIRES_ACTION);
        assertThat(result.checkoutUrl()).isEqualTo("https://checkout.flutterwave.test/session/1");
        provider.verify(1, postRequestedFor(urlEqualTo("/payments")));
        var sent = new ObjectMapper().readTree(provider.getAllServeEvents().getFirst().getRequest().getBody());
        assertThat(sent.path("tx_ref").asText()).isEqualTo("PAY-FLW-1");
        assertThat(sent.path("amount").decimalValue()).isEqualByComparingTo("5000.00");
    }

    @Test
    void verifiesByMerchantReferenceAndMapsProviderTruth() {
        provider.stubFor(get(urlPathEqualTo("/transactions/verify_by_reference"))
                .withQueryParam("tx_ref", equalTo("PAY-FLW-2"))
                .willReturn(okJson("""
                        {"status":"success","data":{"id":123456,"tx_ref":"PAY-FLW-2","amount":5000.00,
                         "currency":"NGN","status":"successful","payment_type":"card","app_fee":75.00,
                         "created_at":"2026-10-30T10:15:30Z"}}
                        """)));

        var verified = adapter.verify("PAY-FLW-2", null);

        assertThat(verified.providerPaymentId()).isEqualTo("123456");
        assertThat(verified.amountMinor()).isEqualTo(500_000L);
        assertThat(verified.feeMinor()).isEqualTo(7_500L);
        assertThat(verified.state()).isEqualTo(ProviderPaymentState.SUCCEEDED);
    }

    @Test
    void initiatesRefundAndKeepsFlutterwaveCompletedInProcessingUntilDisbursementCompletes() {
        provider.stubFor(post(urlEqualTo("/transactions/123456/refund"))
                .willReturn(okJson("""
                        {"status":"success","data":{"id":778899,"tx_id":123456,"flw_ref":"FLW-REF-991",
                         "amount_refunded":2500.00,"status":"completed"}}
                        """)));

        var result = adapter.refund(new ProviderRefundRequest("PAY-FLW-1", "123456", "REF-FLW-1",
                250_000, "NGN", "customer request", "local-refund-key"));

        assertThat(result.providerRefundId()).isEqualTo("778899");
        assertThat(result.amountMinor()).isEqualTo(250_000L);
        assertThat(result.state()).isEqualTo(ProviderRefundState.PROCESSING);
        var sent = new ObjectMapper().readTree(provider.getAllServeEvents().getFirst().getRequest().getBody());
        assertThat(sent.path("amount").decimalValue()).isEqualByComparingTo("2500.00");
        assertThat(sent.path("comments").asText()).contains("REF-FLW-1");
    }

    @Test
    void retrievesRefundByProviderRefundId() {
        provider.stubFor(get(urlEqualTo("/refunds/778899"))
                .willReturn(okJson("""
                        {"status":"success","data":{"id":778899,"flw_ref":"FLW-REF-991",
                         "amount_refunded":2500.00,"status":"completed-mpgs"}}
                        """)));

        var verified = adapter.verifyRefund("778899", "NGN");

        assertThat(verified.providerRefundId()).isEqualTo("778899");
        assertThat(verified.state()).isEqualTo(ProviderRefundState.SUCCEEDED);
        assertThat(verified.amountMinor()).isEqualTo(250_000L);
    }

    @Test
    void discoversRefundAfterTheCreateResponseWasLostWithoutRetryingThePost() {
        provider.stubFor(get(urlPathEqualTo("/refunds"))
                .withQueryParam("id", equalTo("123456"))
                .willReturn(okJson("""
                        {"status":"success","data":[{"id":778899,"amount_refunded":2500.00,
                         "status":"completed","comment":"Local refund: REF-FLW-LOST"}]}
                        """)));

        var found = adapter.findRefundsForPayment("123456", "REF-FLW-LOST", "NGN");

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().providerRefundId()).isEqualTo("778899");
        provider.verify(0, postRequestedFor(urlPathMatching(".*/refund")));
    }
}
