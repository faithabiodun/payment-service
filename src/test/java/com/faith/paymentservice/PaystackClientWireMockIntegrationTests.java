package com.faith.paymentservice;

import com.faith.pay.error.ProviderCommunicationException;
import com.faith.pay.config.PaystackConfig;
import com.faith.pay.config.PaystackProperties;
import com.faith.pay.provider.PaystackClient;
import com.faith.pay.provider.dto.PaystackRefundData;
import com.faith.pay.provider.dto.PaystackTransactionData;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigInteger;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PaystackClientWireMockIntegrationTests.ClientConfiguration.class)
class PaystackClientWireMockIntegrationTests {
    private static final WireMockServer provider = new WireMockServer(options().dynamicPort());

    static {
        provider.start();
    }

    @Autowired PaystackClient paystack;

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("paystack.base-url", provider::baseUrl);
        registry.add("paystack.secret-key", () -> "test-secret");
        registry.add("paystack.callback-url", () -> "http://localhost/callback");
    }

    @AfterAll
    static void stopProvider() { provider.stop(); }

    @BeforeEach
    void resetMappings() { provider.resetAll(); }

    @Test
    void initializeSendsPaystackPayloadAndParsesAuthorizationResponse() {
        provider.stubFor(post(urlEqualTo("/transaction/initialize"))
                .withHeader("Authorization", equalTo("Bearer test-secret"))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("275000")))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("PAY-WIRE-1")))
                .willReturn(okJson("""
                        {"status":true,"message":"ok","data":{"authorization_url":"https://checkout.test/1","access_code":"access-1","reference":"PAY-WIRE-1"}}
                        """)));

        var initialized = paystack.initialize("wire@example.test", 275_000L, "PAY-WIRE-1");

        assertEquals("https://checkout.test/1", initialized.authorizationUrl());
        assertEquals("access-1", initialized.accessCode());
        provider.verify(1, postRequestedFor(urlEqualTo("/transaction/initialize")));
    }

    @Test
    void verifyDeserializesSuccessfulProviderTransaction() {
        provider.stubFor(get(urlEqualTo("/transaction/verify/PAY-WIRE-2"))
                .willReturn(okJson("""
                        {"status":true,"message":"ok","data":{"id":81002,"status":"success","reference":"PAY-WIRE-2","amount":99000,"currency":"NGN","channel":"card","fees":125,"paid_at":"2026-10-28T12:00:00Z"}}
                        """)));

        PaystackTransactionData verified = paystack.verify("PAY-WIRE-2");

        assertEquals(BigInteger.valueOf(81002), verified.id());
        assertEquals("success", verified.status());
        assertEquals("PAY-WIRE-2", verified.reference());
        assertEquals(99_000L, verified.amount());
        assertEquals("NGN", verified.currency());
        assertEquals(Instant.parse("2026-10-28T12:00:00Z"), verified.paidAt());
    }

    @Test
    void refundLookupDeserializesMerchantNoteForLostResponseDiscovery() {
        provider.stubFor(get(urlPathEqualTo("/refund"))
                .withQueryParam("transaction", equalTo("81003"))
                .willReturn(okJson("""
                        {"status":true,"message":"ok","data":[{"id":91003,"status":"processed","amount":44000,"currency":"NGN","merchant_note":"Local refund: REF-WIRE-3"}]}
                        """)));
        provider.stubFor(get(urlEqualTo("/refund/91003"))
                .willReturn(okJson("""
                        {"status":true,"message":"ok","data":{"id":91003,"status":"processed","amount":44000,"currency":"NGN","merchant_note":"Local refund: REF-WIRE-3"}}
                        """)));

        PaystackRefundData listed = paystack.listRefundsForTransaction("81003").getFirst();
        PaystackRefundData fetched = paystack.fetchRefund("91003");

        assertEquals("Local refund: REF-WIRE-3", listed.merchantNote());
        assertEquals("91003", listed.id().toString());
        assertEquals("processed", fetched.status());
        provider.verify(1, getRequestedFor(urlPathEqualTo("/refund"))
                .withQueryParam("transaction", equalTo("81003")));
    }

    @Test
    void providerServerFailureIsReportedAsCommunicationFailure() {
        provider.stubFor(get(urlEqualTo("/transaction/verify/PAY-WIRE-500"))
                .willReturn(aResponse().withStatus(500).withBody("provider unavailable")));

        assertThrows(ProviderCommunicationException.class, () -> paystack.verify("PAY-WIRE-500"));
    }

    @Test
    void providerTimeoutIsReportedAsCommunicationFailure() {
        provider.stubFor(get(urlEqualTo("/transaction/verify/PAY-WIRE-TIMEOUT"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(11_000)
                        .withBody("{\"status\":true,\"data\":{}}")));

        assertThrows(ProviderCommunicationException.class, () -> paystack.verify("PAY-WIRE-TIMEOUT"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaystackProperties.class)
    @Import({PaystackConfig.class, PaystackClient.class})
    static class ClientConfiguration { }
}
