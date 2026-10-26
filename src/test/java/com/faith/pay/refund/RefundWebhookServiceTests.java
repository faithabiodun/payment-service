package com.faith.pay.refund;

import com.faith.pay.order.Order;
import com.faith.pay.payment.Payment;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.PaystackClient;
import com.faith.pay.provider.dto.PaystackRefundData;
import com.faith.pay.webhook.PaystackRefundWebhookData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;

class RefundWebhookServiceTests {

    private RefundRepository refunds;
    private PaystackClient paystack;
    private RefundStateService states;
    private RefundWebhookService webhooks;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        refunds = mock(RefundRepository.class);
        paystack = mock(PaystackClient.class);
        states = mock(RefundStateService.class);
        webhooks = new RefundWebhookService(refunds, paystack, states);
        mapper = new ObjectMapper();
    }

    @Test
    void fetchesCurrentProviderStateWhenRefundIdIsKnown() throws Exception {
        Refund refund = refund("REF-1", "PAY-1", "9001");
        when(refunds.findNonTerminalByPaymentReference("PAY-1"))
                .thenReturn(List.of(refund));
        PaystackRefundData current = new PaystackRefundData(
                BigInteger.valueOf(9001), "processed", 250_000L, "NGN", null, Instant.now());
        when(paystack.fetchRefund("9001")).thenReturn(current);

        webhooks.process("refund.processed", event("processed", "250000", null, "PAY-1"), "{}");

        verify(paystack).fetchRefund("9001");
        verify(states).applyProviderState(
                "REF-1", current, PaymentEventSource.WEBHOOK, "{}");
    }

    @Test
    void appliesSignedEventWhenThereIsOneUnambiguousLocalRefundAndNoProviderIdYet() throws Exception {
        Refund refund = refund("REF-2", "PAY-2", null);
        when(refunds.findNonTerminalByPaymentReference("PAY-2"))
                .thenReturn(List.of(refund));

        webhooks.process("refund.pending", event("pending", "250000", null, "PAY-2"), "{signed}");

        verify(paystack, never()).fetchRefund(anyString());
        verify(states).applyProviderState(
                eq("REF-2"),
                argThat(data -> data.id() == null
                        && "pending".equals(data.status())
                        && data.amount() == 250_000L
                        && "NGN".equals(data.currency())),
                eq(PaymentEventSource.WEBHOOK),
                eq("{signed}")
        );
    }

    @Test
    void doesNotGuessWhenSamePaymentHasMultipleMatchingRefunds() throws Exception {
        Refund first = refund("REF-3", "PAY-3", null);
        Refund second = refund("REF-4", "PAY-3", null);
        when(refunds.findNonTerminalByPaymentReference("PAY-3"))
                .thenReturn(List.of(first, second));

        webhooks.process("refund.processed", event("processed", "250000", null, "PAY-3"), "{}");

        verifyNoInteractions(paystack, states);
    }

    private PaystackRefundWebhookData event(
            String status,
            String amount,
            String refundReference,
            String paymentReference
    )
            throws Exception {
        return new PaystackRefundWebhookData(status, paymentReference, refundReference,
                mapper.readTree("\"" + amount + "\""), "NGN");
    }

    private Refund refund(String refundReference, String paymentReference, String providerRefundId) {
        Payment payment = new Payment(paymentReference,
                new Order("refund@example.test", 1_000_000L), 1_000_000L, "pay-key");
        Refund refund = new Refund(payment, refundReference, 250_000L,
                250_000L, "Partial", "refund-key-" + refundReference);
        if (providerRefundId != null) {
            refund.markProcessing(providerRefundId, "processing");
        }
        return refund;
    }
}
