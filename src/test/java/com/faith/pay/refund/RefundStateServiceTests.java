package com.faith.pay.refund;

import com.faith.pay.error.AppException;
import com.faith.pay.order.Order;
import com.faith.pay.payment.Payment;
import com.faith.pay.payment.PaymentRepository;
import com.faith.pay.payment.PaymentStatus;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.payment.audit.PaymentEventStore;
import com.faith.pay.provider.dto.PaystackRefundData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefundStateServiceTests {

    private RefundRepository refunds;
    private PaymentRepository payments;
    private PaymentEventStore events;
    private RefundStateService states;
    private Refund refund;
    private Payment payment;

    @BeforeEach
    void setUp() {
        refunds = mock(RefundRepository.class);
        payments = mock(PaymentRepository.class);
        events = mock(PaymentEventStore.class);
        states = new RefundStateService(refunds, payments, events);

        payment = new Payment("PAY-1", new Order("customer@example.test", 1_000_000L),
                1_000_000L, "payment-key");
        payment.markSuccessful("123", "card", 0L, Instant.now());
        refund = new Refund(payment, "REF-1", 250_000L, 250_000L, "Partial", "refund-key");

        when(refunds.findByReference("REF-1")).thenReturn(Optional.of(refund));
        when(refunds.findByReferenceForUpdate("REF-1")).thenReturn(Optional.of(refund));
        when(payments.findByReferenceForUpdate("PAY-1")).thenReturn(Optional.of(payment));
        when(refunds.findByProviderRefundId("9001")).thenReturn(Optional.empty());
    }

    @Test
    void pendingAndNeedsAttentionReserveWithoutChangingRefundedTotal() {
        states.applyProviderState("REF-1", provider("pending"), PaymentEventSource.REFUND, null);
        assertEquals(RefundStatus.PROCESSING, refund.getStatus());
        assertEquals(0L, payment.getRefundedKobo());

        states.applyProviderState("REF-1", provider("needs-attention"), PaymentEventSource.REFUND, null);
        assertEquals(RefundStatus.NEEDS_ATTENTION, refund.getStatus());
        assertEquals(0L, payment.getRefundedKobo());
        assertTrue(refund.getStatus().reservesMoney());
    }

    @Test
    void processedRefundAppliesExactlyOnceAndUpdatesPaymentStatus() {
        states.applyProviderState("REF-1", provider("processed"), PaymentEventSource.REFUND, null);
        assertEquals(RefundStatus.SUCCESS, refund.getStatus());
        assertEquals(250_000L, payment.getRefundedKobo());
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, payment.getStatus());

        states.applyProviderState("REF-1", provider("processed"), PaymentEventSource.WEBHOOK, "{}");
        assertEquals(250_000L, payment.getRefundedKobo());
        verify(events, times(1)).append(payment,
                com.faith.pay.payment.audit.PaymentEventType.REFUND_SUCCEEDED,
                PaymentStatus.SUCCESS, PaymentStatus.PARTIALLY_REFUNDED,
                PaymentEventSource.REFUND, null);
    }

    @Test
    void failedRefundReleasesCapacityAndCannotLaterBecomeSuccessful() {
        states.applyProviderState("REF-1", provider("failed"), PaymentEventSource.REFUND, null);
        assertEquals(RefundStatus.FAILED, refund.getStatus());
        assertFalse(refund.getStatus().reservesMoney());
        assertEquals(0L, payment.getRefundedKobo());

        assertThrows(AppException.class,
                () -> states.applyProviderState("REF-1", provider("processed"),
                        PaymentEventSource.WEBHOOK, "{}"));
        assertEquals(0L, payment.getRefundedKobo());
    }

    @Test
    void mismatchedProviderAmountOrCurrencyIsRejected() {
        assertThrows(AppException.class, () -> states.applyProviderState("REF-1",
                new PaystackRefundData(BigInteger.valueOf(9001), "processed", 1L, "NGN", null, null),
                PaymentEventSource.REFUND, null));
        assertThrows(AppException.class, () -> states.applyProviderState("REF-1",
                new PaystackRefundData(BigInteger.valueOf(9001), "processed", 250_000L, "USD", null, null),
                PaymentEventSource.REFUND, null));
        assertEquals(0L, payment.getRefundedKobo());
    }

    private PaystackRefundData provider(String status) {
        return new PaystackRefundData(BigInteger.valueOf(9001), status,
                250_000L, "NGN", null, null);
    }
}
