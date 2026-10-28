package com.faith.paymentservice;

import com.faith.pay.order.Order;
import com.faith.pay.order.OrderRepository;
import com.faith.pay.payment.*;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.PaystackClient;
import com.faith.pay.provider.dto.PaystackRefundData;
import com.faith.pay.provider.dto.PaystackTransactionData;
import com.faith.pay.reconciliation.PaymentReconciler;
import com.faith.pay.reconciliation.RefundReconciler;
import com.faith.pay.refund.Refund;
import com.faith.pay.refund.RefundRepository;
import com.faith.pay.refund.RefundStatus;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import com.faith.paymentservice.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigInteger;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "paystack.secret-key=test-secret")
class PaymentReconciliationIntegrationTests {
    @Autowired PaymentReconciler paymentReconciler;
    @Autowired RefundReconciler refundReconciler;
    @Autowired PaymentRepository payments;
    @Autowired PaymentCommandService paymentCommands;
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired JdbcTemplate jdbc;
    @Autowired LockProvider lockProvider;
    @MockitoBean PaystackClient paystack;

    @BeforeEach
    void resetProvider() { reset(paystack); }

    @Test
    void missedPaymentWebhookIsRecoveredIdempotentlyThroughCanonicalSettlement() {
        Payment payment = openPayment(300_000L);
        PaystackTransactionData success = transaction(payment, "success", 40001L);
        when(paystack.verify(payment.getReference())).thenReturn(success);

        paymentReconciler.reconcile(candidate(payment));
        paymentReconciler.reconcile(candidate(payment));

        Payment refreshed = payments.findByReference(payment.getReference()).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, refreshed.getStatus());
        assertEquals("PAID", jdbc.queryForObject("select status from orders where id=?", String.class,
                refreshed.getOrder().getId()));
        assertEquals(1, paymentEventCount(payment, "PAYMENT_SUCCEEDED"));
        assertEquals("RECONCILIATION", paymentEventSource(payment, "PAYMENT_SUCCEEDED"));
        assertEquals(2, reconciliationOutcomeCount("PAYMENT", payment.getReference()));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from reconciliation_attempts
                where target_type='PAYMENT' and target_reference=? and outcome='RECOVERED'
                """, Integer.class, payment.getReference()));
    }

    @Test
    void providerTimeoutLeavesPaymentOpenAndRecordsAttempt() {
        Payment payment = openPayment(150_000L);
        when(paystack.verify(payment.getReference())).thenThrow(
                new com.faith.pay.error.ProviderCommunicationException("provider timeout"));

        paymentReconciler.reconcile(candidate(payment));

        Payment refreshed = payments.findByReference(payment.getReference()).orElseThrow();
        assertEquals(PaymentStatus.PROCESSING, refreshed.getStatus());
        assertEquals(1, refreshed.getReconciliationAttempts());
        assertEquals("PROVIDER_UNAVAILABLE", lastReconciliationOutcome("PAYMENT", payment.getReference()));
    }

    @Test
    void providerFailureMarksOpenPaymentFailedButDoesNotMarkOrderPaid() {
        Payment payment = openPayment(70_000L);
        when(paystack.verify(payment.getReference())).thenReturn(transaction(payment, "failed", 40002L));

        paymentReconciler.reconcile(candidate(payment));

        assertEquals(PaymentStatus.FAILED, payments.findByReference(payment.getReference()).orElseThrow().getStatus());
        assertEquals("UNPAID", jdbc.queryForObject("select status from orders where id=?", String.class,
                payment.getOrder().getId()));
        assertEquals("RECONCILIATION", paymentEventSource(payment, "PAYMENT_FAILED"));
    }

    @Test
    void providerPendingKeepsPaymentOpenAndRecordsCheck() {
        Payment payment = openPayment(80_000L);
        when(paystack.verify(payment.getReference())).thenReturn(transaction(payment, "pending", 40004L));

        paymentReconciler.reconcile(candidate(payment));

        Payment refreshed = payments.findByReference(payment.getReference()).orElseThrow();
        assertEquals(PaymentStatus.PROCESSING, refreshed.getStatus());
        assertEquals(1, refreshed.getReconciliationAttempts());
        assertEquals("STILL_OPEN", lastReconciliationOutcome("PAYMENT", payment.getReference()));
    }

    @Test
    void oldAbandonedPaymentCanBeReplacedByANewAttempt() {
        Payment payment = openPayment(81_000L);
        jdbc.update("update payments set created_at=now()-interval '48 hours', updated_at=now()-interval '48 hours' where reference=?",
                payment.getReference());
        Payment oldCandidate = candidate(payment);
        when(paystack.verify(payment.getReference())).thenReturn(transaction(payment, "abandoned", 40005L));

        paymentReconciler.reconcile(oldCandidate);

        assertEquals(PaymentStatus.ABANDONED, payments.findByReference(payment.getReference()).orElseThrow().getStatus());
        assertEquals("MARKED_ABANDONED", lastReconciliationOutcome("PAYMENT", payment.getReference()));
        PaymentReservation next = paymentCommands.reserve(payment.getOrder().getId(), "retry-after-abandon-" + UUID.randomUUID());
        assertNotEquals(payment.getReference(), next.reference());
    }

    @Test
    void unknownProviderStatusRequiresReviewRatherThanGuessing() {
        Payment payment = openPayment(82_000L);
        when(paystack.verify(payment.getReference())).thenReturn(transaction(payment, "reversed", 40006L));

        paymentReconciler.reconcile(candidate(payment));

        Payment reviewed = payments.findByReference(payment.getReference()).orElseThrow();
        assertEquals(PaymentStatus.PROCESSING, reviewed.getStatus());
        assertTrue(reviewed.isManualReviewRequired());
        assertEquals("MANUAL_REVIEW", lastReconciliationOutcome("PAYMENT", payment.getReference()));
    }

    @Test
    void contradictoryPaymentStateIsFlaggedAndNeverRewritten() {
        Payment payment = successfulPayment(200_000L);
        when(paystack.verify(payment.getReference())).thenReturn(transaction(payment, "failed", 40003L));

        paymentReconciler.reconcile(candidate(payment));

        Payment refreshed = payments.findByReference(payment.getReference()).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, refreshed.getStatus());
        assertTrue(refreshed.isManualReviewRequired());
        assertEquals("MANUAL_REVIEW", lastReconciliationOutcome("PAYMENT", payment.getReference()));
    }

    @Test
    void knownProviderRefundIsFetchedAndAppliedOnce() {
        Payment payment = successfulPayment(600_000L);
        Refund refund = processingRefund(payment, 250_000L, "5001");
        when(paystack.fetchRefund("5001")).thenReturn(refundData(5001, "processed", 250_000L, null));

        refundReconciler.reconcile(refundCandidate(refund));
        refundReconciler.reconcile(refundCandidate(refund));

        assertEquals(RefundStatus.SUCCESS, refunds.findByReference(refund.getReference()).orElseThrow().getStatus());
        Payment refreshedPayment = payments.findByReference(payment.getReference()).orElseThrow();
        assertEquals(250_000L, refreshedPayment.getRefundedKobo());
        assertEquals(1, paymentEventCount(payment, "REFUND_SUCCEEDED"));
        verify(paystack, times(2)).fetchRefund("5001");
        verify(paystack, never()).createRefund(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void lostRefundCreationResponseIsRecoveredByMerchantNoteWithoutPostingAgain() {
        Payment payment = successfulPayment(400_000L);
        Refund refund = pendingRefund(payment, 120_000L);
        PaystackRefundData matching = refundData(6001, "processed", 120_000L,
                "Local refund: " + refund.getReference() + " | lost HTTP response");
        when(paystack.listRefundsForTransaction(payment.getProviderTransactionId())).thenReturn(List.of(matching));

        refundReconciler.reconcile(refundCandidate(refund));

        Refund recovered = refunds.findByReference(refund.getReference()).orElseThrow();
        assertEquals("6001", recovered.getProviderRefundId());
        assertEquals(RefundStatus.SUCCESS, recovered.getStatus());
        assertEquals(120_000L, payments.findByReference(payment.getReference()).orElseThrow().getRefundedKobo());
        assertEquals("RECOVERED", lastReconciliationOutcome("REFUND", refund.getReference()));
        verify(paystack, never()).createRefund(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void multipleMatchingProviderRefundsRequireReviewWithoutApplyingMoney() {
        Payment payment = successfulPayment(400_000L);
        Refund refund = pendingRefund(payment, 120_000L);
        when(paystack.listRefundsForTransaction(payment.getProviderTransactionId())).thenReturn(List.of(
                refundData(6002, "processed", 120_000L, "Local refund: " + refund.getReference()),
                refundData(6003, "processed", 120_000L, "Local refund: " + refund.getReference())));

        refundReconciler.reconcile(refundCandidate(refund));

        Refund reviewed = refunds.findByReference(refund.getReference()).orElseThrow();
        assertTrue(reviewed.isManualReviewRequired());
        assertEquals(RefundStatus.PENDING, reviewed.getStatus());
        assertEquals(0L, payments.findByReference(payment.getReference()).orElseThrow().getRefundedKobo());
        assertEquals("MANUAL_REVIEW", lastReconciliationOutcome("REFUND", refund.getReference()));
        verify(paystack, never()).createRefund(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void shedLockAllowsOnlyOneWorkerToAcquireAJobLease() {
        String lockName = "integration-lock-" + UUID.randomUUID();
        var first = lockProvider.lock(new LockConfiguration(Instant.now(), lockName,
                Duration.ofMinutes(1), Duration.ZERO));
        assertTrue(first.isPresent());
        try {
            var competing = lockProvider.lock(new LockConfiguration(Instant.now(), lockName,
                    Duration.ofMinutes(1), Duration.ZERO));
            assertTrue(competing.isEmpty());
        } finally {
            first.orElseThrow().unlock();
        }
        var reacquired = lockProvider.lock(new LockConfiguration(Instant.now(), lockName,
                Duration.ofMinutes(1), Duration.ZERO));
        assertTrue(reacquired.isPresent());
        reacquired.orElseThrow().unlock();
    }

    private Payment openPayment(long amount) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Order order = orders.saveAndFlush(new Order("reconcile@example.test", amount));
        Payment payment = new Payment("PAY-REC-" + suffix, order, amount, "idem-rec-" + suffix);
        payment.markProcessing("https://pay.test", "access");
        return payments.saveAndFlush(payment);
    }

    private Payment successfulPayment(long amount) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Order order = orders.saveAndFlush(new Order("reconcile@example.test", amount));
        order.markPaid();
        orders.saveAndFlush(order);
        Payment payment = new Payment("PAY-REC-" + suffix, order, amount, "idem-rec-" + suffix);
        payment.markSuccessful("provider-rec-" + suffix, "card", 0L, Instant.now());
        return payments.saveAndFlush(payment);
    }

    private Refund pendingRefund(Payment payment, long amount) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return refunds.saveAndFlush(new Refund(payment, "REF-REC-" + suffix, amount, amount,
                "test", "idem-ref-rec-" + suffix));
    }

    private Refund processingRefund(Payment payment, long amount, String providerId) {
        Refund refund = pendingRefund(payment, amount);
        refund.markProcessing(providerId, "processing");
        return refunds.saveAndFlush(refund);
    }

    private Payment candidate(Payment payment) {
        return payments.findByReference(payment.getReference()).orElseThrow();
    }

    private Refund refundCandidate(Refund refund) {
        return refunds.findByReference(refund.getReference()).orElseThrow();
    }

    private PaystackTransactionData transaction(Payment payment, String status, long id) {
        return new PaystackTransactionData(BigInteger.valueOf(id), status, payment.getReference(),
                payment.getAmountKobo(), "NGN", "card", 0L, Instant.now());
    }

    private PaystackRefundData refundData(long id, String status, long amount, String note) {
        return new PaystackRefundData(BigInteger.valueOf(id), status, amount, "NGN", null, Instant.now(), note);
    }

    private int paymentEventCount(Payment payment, String event) {
        return jdbc.queryForObject("""
                select count(*) from payment_events e join payments p on p.id=e.payment_id
                where p.reference=? and e.event_type=?
                """, Integer.class, payment.getReference(), event);
    }

    private String paymentEventSource(Payment payment, String event) {
        return jdbc.queryForObject("""
                select e.source from payment_events e join payments p on p.id=e.payment_id
                where p.reference=? and e.event_type=? order by e.id desc limit 1
                """, String.class, payment.getReference(), event);
    }

    private int reconciliationOutcomeCount(String type, String reference) {
        return jdbc.queryForObject("select count(*) from reconciliation_attempts where target_type=? and target_reference=?",
                Integer.class, type, reference);
    }

    private String lastReconciliationOutcome(String type, String reference) {
        return jdbc.queryForObject("""
                select outcome from reconciliation_attempts where target_type=? and target_reference=?
                order by id desc limit 1
                """, String.class, type, reference);
    }
}
