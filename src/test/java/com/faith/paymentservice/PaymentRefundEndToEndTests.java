package com.faith.paymentservice;

import com.faith.pay.order.Order;
import com.faith.pay.order.OrderRepository;
import com.faith.pay.error.ProviderCommunicationException;
import com.faith.pay.payment.Payment;
import com.faith.pay.payment.PaymentRepository;
import com.faith.pay.payment.PaymentStatus;
import com.faith.pay.reconciliation.RefundReconciler;
import com.faith.pay.provider.PaystackClient;
import com.faith.pay.provider.dto.PaystackInitializeData;
import com.faith.pay.provider.dto.PaystackRefundData;
import com.faith.pay.provider.dto.PaystackTransactionData;
import com.faith.pay.refund.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "paystack.secret-key=test-secret")
@AutoConfigureMockMvc
class PaymentRefundEndToEndTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OrderRepository orders;
    @Autowired PaymentRepository payments;
    @Autowired RefundRepository refunds;
    @Autowired RefundReconciler refundReconciler;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean PaystackClient paystack;

    @BeforeEach
    void resetProvider() { reset(paystack); }

    @Test
    void httpOrderInitializationAndManualVerificationSettleExactlyOnce() throws Exception {
        long amount = 125_000L;
        JsonNode orderResponse = json.readTree(mvc.perform(post("/api/v1/test-orders")
                        .contentType("application/json")
                        .content("{\"email\":\"e2e@example.test\",\"amountKobo\":" + amount + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long orderId = orderResponse.get("id").asLong();

        when(paystack.initialize(eq("e2e@example.test"), eq(amount), anyString()))
                .thenAnswer(invocation -> new PaystackInitializeData("https://pay.test/checkout", "access-code",
                        invocation.getArgument(2)));
        JsonNode initialized = json.readTree(mvc.perform(post("/api/v1/payments/initialize")
                        .header("Idempotency-Key", "e2e-payment-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"orderId\":" + orderId + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String reference = initialized.get("reference").asString();

        when(paystack.verify(reference)).thenReturn(transaction(reference, amount, "success", 9123L));
        mvc.perform(post("/api/v1/payments/{reference}/verify", reference))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementApplied").value(true))
                .andExpect(jsonPath("$.payment.status").value("SUCCESS"));
        mvc.perform(post("/api/v1/payments/{reference}/verify", reference))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementApplied").value(false));

        assertEquals("PAID", jdbc.queryForObject("select status from orders where id = ?", String.class, orderId));
        assertEquals(1, eventCount(reference, "PAYMENT_SUCCEEDED"));
        assertEquals("VERIFICATION", eventSource(reference, "PAYMENT_SUCCEEDED"));
        verify(paystack, times(1)).initialize(eq("e2e@example.test"), eq(amount), anyString());
    }

    @Test
    void signedWebhookSettlesPaymentAndRejectsInvalidSignatureBeforeChangingState() throws Exception {
        Payment payment = openPayment(90_000L);
        String body = "{\"event\":\"charge.success\",\"data\":{" +
                "\"id\":81234,\"status\":\"success\",\"reference\":\"" + payment.getReference() + "\"," +
                "\"amount\":90000,\"currency\":\"NGN\",\"channel\":\"card\",\"fees\":200," +
                "\"paid_at\":\"2026-10-28T12:00:00Z\"}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String signature = signature(bytes);

        mvc.perform(post("/webhooks/paystack").contentType("application/json").content(bytes)
                        .header("x-paystack-signature", "00"))
                .andExpect(status().isUnauthorized());
        assertEquals(PaymentStatus.PROCESSING, payments.findByReference(payment.getReference()).orElseThrow().getStatus());

        mvc.perform(post("/webhooks/paystack").contentType("application/json").content(bytes)
                        .header("x-paystack-signature", signature))
                .andExpect(status().isOk());
        mvc.perform(post("/webhooks/paystack").contentType("application/json").content(bytes)
                        .header("x-paystack-signature", signature))
                .andExpect(status().isOk());

        assertEquals(PaymentStatus.SUCCESS, payments.findByReference(payment.getReference()).orElseThrow().getStatus());
        assertEquals(1, eventCount(payment.getReference(), "PAYMENT_SUCCEEDED"));
        assertEquals("WEBHOOK", eventSource(payment.getReference(), "PAYMENT_SUCCEEDED"));
    }

    @Test
    void refundHttpFlowAppliesProcessedProviderResponseOnce() throws Exception {
        Payment payment = successfulPayment(500_000L);
        when(paystack.createRefund(eq(payment.getReference()), eq(200_000L), anyString(), eq("customer request")))
                .thenAnswer(invocation -> new PaystackRefundData(BigInteger.valueOf(9911), "processed",
                        200_000L, "NGN", null, Instant.now()));

        JsonNode response = json.readTree(mvc.perform(post("/api/v1/payments/{reference}/refunds", payment.getReference())
                        .header("Idempotency-Key", "e2e-refund-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"amountKobo\":200000,\"reason\":\"customer request\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString());

        String refundReference = response.get("reference").asString();
        Payment refreshed = payments.findByReference(payment.getReference()).orElseThrow();
        assertEquals(200_000L, refreshed.getRefundedKobo());
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, refreshed.getStatus());
        assertEquals(1, eventCount(payment.getReference(), "REFUND_SUCCEEDED"));
        assertEquals("REFUND", eventSource(payment.getReference(), "REFUND_SUCCEEDED"));
        assertEquals("9911", refunds.findByReference(refundReference).orElseThrow().getProviderRefundId());
        verify(paystack, times(1)).createRefund(eq(payment.getReference()), eq(200_000L), anyString(), eq("customer request"));
    }

    @Test
    void lostRefundHttpResponseIsDiscoveredWithoutSubmittingAnotherRefund() throws Exception {
        Payment payment = successfulPayment(450_000L);
        String idempotencyKey = "e2e-refund-lost-" + UUID.randomUUID();
        when(paystack.createRefund(eq(payment.getReference()), eq(175_000L), anyString(), eq("lost response")))
                .thenThrow(new ProviderCommunicationException("response timed out after provider accepted request"));

        mvc.perform(post("/api/v1/payments/{reference}/refunds", payment.getReference())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content("{\"amountKobo\":175000,\"reason\":\"lost response\"}"))
                .andExpect(status().isServiceUnavailable());

        var localRefunds = refunds.findNonTerminalByPaymentReference(payment.getReference());
        assertEquals(1, localRefunds.size());
        var localRefund = localRefunds.getFirst();
        assertEquals("PENDING", localRefund.getStatus().name());
        assertNull(localRefund.getProviderRefundId());
        when(paystack.listRefundsForTransaction(payment.getProviderTransactionId())).thenReturn(java.util.List.of(
                new PaystackRefundData(BigInteger.valueOf(9931), "processed", 175_000L, "NGN",
                        null, Instant.now(), "Local refund: " + localRefund.getReference())));

        refundReconciler.reconcile(localRefund);

        assertEquals("SUCCESS", refunds.findByReference(localRefund.getReference()).orElseThrow().getStatus().name());
        assertEquals(175_000L, payments.findByReference(payment.getReference()).orElseThrow().getRefundedKobo());
        verify(paystack, times(1)).createRefund(eq(payment.getReference()), eq(175_000L), anyString(), eq("lost response"));
        verify(paystack, times(1)).listRefundsForTransaction(payment.getProviderTransactionId());
    }

    @Test
    void uncertainInitializationRemainsReservedForLaterReconciliation() throws Exception {
        long amount = 65_000L;
        JsonNode order = json.readTree(mvc.perform(post("/api/v1/test-orders")
                        .contentType("application/json")
                        .content("{\"email\":\"uncertain@example.test\",\"amountKobo\":" + amount + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String idempotencyKey = "uncertain-" + UUID.randomUUID();
        when(paystack.initialize(eq("uncertain@example.test"), eq(amount), anyString()))
                .thenThrow(new ProviderCommunicationException("connection lost after request"));

        mvc.perform(post("/api/v1/payments/initialize")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content("{\"orderId\":" + order.get("id").asLong() + "}"))
                .andExpect(status().isServiceUnavailable());

        Payment pending = payments.findByIdempotencyKey(idempotencyKey).orElseThrow();
        assertEquals(PaymentStatus.PENDING, pending.getStatus());
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from payment_events where payment_id=? and event_type='PAYSTACK_INITIALIZATION_UNCERTAIN'
                """, Integer.class, pending.getId()));
    }

    private Payment openPayment(long amount) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Order order = orders.saveAndFlush(new Order("open@example.test", amount));
        Payment payment = new Payment("PAY-E2E-" + suffix, order, amount, "idem-e2e-" + suffix);
        payment.markProcessing("https://pay.test", "access");
        return payments.saveAndFlush(payment);
    }

    private Payment successfulPayment(long amount) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Order order = orders.saveAndFlush(new Order("refund-e2e@example.test", amount));
        order.markPaid();
        orders.saveAndFlush(order);
        Payment payment = new Payment("PAY-E2E-" + suffix, order, amount, "idem-e2e-" + suffix);
        payment.markSuccessful("provider-e2e-" + suffix, "card", 0L, Instant.now());
        return payments.saveAndFlush(payment);
    }

    private PaystackTransactionData transaction(String reference, long amount, String status, long id) {
        return new PaystackTransactionData(BigInteger.valueOf(id), status, reference, amount,
                "NGN", "card", 200L, Instant.now());
    }

    private int eventCount(String paymentReference, String type) {
        return jdbc.queryForObject("""
                select count(*) from payment_events e join payments p on p.id=e.payment_id
                where p.reference=? and e.event_type=?
                """, Integer.class, paymentReference, type);
    }

    private String eventSource(String paymentReference, String type) {
        return jdbc.queryForObject("""
                select e.source from payment_events e join payments p on p.id=e.payment_id
                where p.reference=? and e.event_type=? order by e.id desc limit 1
                """, String.class, paymentReference, type);
    }

    private String signature(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
