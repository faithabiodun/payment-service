package com.faith.pay.reconciliation;

import com.faith.pay.payment.PaymentRepository;
import com.faith.pay.refund.RefundRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentReconciler paymentReconciler;
    private final RefundReconciler refundReconciler;
    private final ReconciliationProperties properties;

    public ReconciliationJob(PaymentRepository payments, RefundRepository refunds,
                             PaymentReconciler paymentReconciler, RefundReconciler refundReconciler,
                             ReconciliationProperties properties) {
        this.payments = payments; this.refunds = refunds; this.paymentReconciler = paymentReconciler;
        this.refundReconciler = refundReconciler; this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${reconciliation.payment-delay:PT5M}")
    @SchedulerLock(name = "payment-reconciliation", lockAtMostFor = "PT30M")
    public void reconcilePayments() {
        var candidates = payments.findReconciliationCandidates(Instant.now().minus(properties.paymentStaleAfter()),
                PageRequest.of(0, Math.max(1, properties.batchSize())));
        for (var candidate : candidates) {
            try { paymentReconciler.reconcile(candidate); }
            catch (RuntimeException failure) { log.error("Payment reconciliation failed for {}", candidate.getReference(), failure); }
        }
    }

    @Scheduled(fixedDelayString = "${reconciliation.refund-delay:PT5M}")
    @SchedulerLock(name = "refund-reconciliation", lockAtMostFor = "PT30M")
    public void reconcileRefunds() {
        var candidates = refunds.findReconciliationCandidates(Instant.now().minus(properties.refundStaleAfter()),
                PageRequest.of(0, Math.max(1, properties.batchSize())));
        for (var candidate : candidates) {
            try { refundReconciler.reconcile(candidate); }
            catch (RuntimeException failure) { log.error("Refund reconciliation failed for {}", candidate.getReference(), failure); }
        }
    }
}
