package com.faith.pay.refund;

import com.faith.pay.order.Order;
import com.faith.pay.order.OrderRepository;
import com.faith.pay.error.RefundAmountExceedsRemainingException;
import com.faith.pay.payment.Payment;
import com.faith.pay.payment.PaymentRepository;
import com.faith.pay.payment.PaymentStatus;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.dto.PaystackRefundData;
import com.faith.paymentservice.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "paystack.secret-key=test-secret")
class RefundInvariantIntegrationTests {

    @Autowired
    private RefundCommandService commands;

    @Autowired
    private RefundStateService states;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private RefundRepository refunds;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentSixtyPercentRefundsCannotBothReserve() throws Exception {
        Payment payment = successfulPayment(1_000_000L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(() -> reserveConcurrently(
                    payment.getReference(), "refund-a-" + UUID.randomUUID(), ready, start));
            Future<Boolean> second = executor.submit(() -> reserveConcurrently(
                    payment.getReference(), "refund-b-" + UUID.randomUUID(), ready, start));

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int reserved = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, reserved);
            assertEquals(600_000L, refunds.sumReservedAmount(payment.getId()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void completedPartialRefundMatchesPaymentAccountingInvariant() {
        Payment payment = successfulPayment(1_000_000L);
        RefundReservation reserved = commands.reserve(
                payment.getReference(),
                new com.faith.pay.refund.dto.RefundRequest(400_000L, "Partial"),
                "refund-" + UUID.randomUUID()
        );

        states.applyProviderState(
                reserved.refundReference(),
                new PaystackRefundData(BigInteger.valueOf(7001), "processed",
                        400_000L, "NGN", null, Instant.now()),
                PaymentEventSource.REFUND,
                null
        );

        Payment refreshed = payments.findByReference(payment.getReference()).orElseThrow();
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, refreshed.getStatus());
        assertEquals(400_000L, refreshed.getRefundedKobo());
        assertEquals(0, countRefundAccountingViolations());
    }

    @Test
    void fullRemainingRefundMovesPaymentToRefundedOnlyAfterProviderConfirmation() {
        Payment payment = successfulPayment(300_000L);
        RefundReservation reserved = commands.reserve(
                payment.getReference(),
                new com.faith.pay.refund.dto.RefundRequest(null, "Full refund"),
                "refund-" + UUID.randomUUID()
        );

        assertEquals(300_000L, reserved.amountKobo());
        assertEquals(0L, payments.findByReference(payment.getReference())
                .orElseThrow().getRefundedKobo());

        states.applyProviderState(
                reserved.refundReference(),
                new PaystackRefundData(BigInteger.valueOf(7002), "processed",
                        300_000L, "NGN", null, Instant.now()),
                PaymentEventSource.REFUND,
                null
        );

        Payment refreshed = payments.findByReference(payment.getReference()).orElseThrow();
        assertEquals(PaymentStatus.REFUNDED, refreshed.getStatus());
        assertEquals(300_000L, refreshed.getRefundedKobo());
        assertEquals(0, countRefundAccountingViolations());
    }

    @Test
    void failedRefundReleasesReservedCapacityForANewRequest() {
        Payment payment = successfulPayment(1_000_000L);
        RefundReservation failedReservation = commands.reserve(
                payment.getReference(),
                new com.faith.pay.refund.dto.RefundRequest(600_000L, "First attempt"),
                "refund-" + UUID.randomUUID()
        );

        states.applyProviderState(
                failedReservation.refundReference(),
                new PaystackRefundData(BigInteger.valueOf(7003), "failed",
                        600_000L, "NGN", null, null),
                PaymentEventSource.REFUND,
                null
        );

        assertEquals(0L, refunds.sumReservedAmount(payment.getId()));
        RefundReservation next = commands.reserve(
                payment.getReference(),
                new com.faith.pay.refund.dto.RefundRequest(1_000_000L, "Replacement attempt"),
                "refund-" + UUID.randomUUID()
        );
        assertEquals(1_000_000L, next.amountKobo());
        assertEquals(0, countRefundAccountingViolations());
    }

    private boolean reserveConcurrently(
            String paymentReference,
            String idempotencyKey,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent refund test did not start");
        }
        try {
            commands.reserve(paymentReference,
                    new com.faith.pay.refund.dto.RefundRequest(600_000L, "Concurrent partial"),
                    idempotencyKey);
            return true;
        } catch (RefundAmountExceedsRemainingException rejected) {
            return false;
        }
    }

    private Payment successfulPayment(long amountKobo) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Order order = orders.saveAndFlush(new Order("refund@example.test", amountKobo));
        order.markPaid();
        Payment payment = new Payment("PAY-REFUND-" + suffix, order, amountKobo,
                "payment-" + suffix);
        payment.markSuccessful("provider-" + suffix, "card", 0L, Instant.now());
        return payments.saveAndFlush(payment);
    }

    private int countRefundAccountingViolations() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT p.id
                    FROM payments p
                    LEFT JOIN refunds r
                        ON r.payment_id = p.id
                       AND r.status = 'SUCCESS'
                    GROUP BY p.id
                    HAVING p.refunded_kobo <> COALESCE(SUM(r.amount_kobo), 0)
                        OR p.refunded_kobo > p.amount_kobo
                ) violations
                """, Integer.class);
    }
}
