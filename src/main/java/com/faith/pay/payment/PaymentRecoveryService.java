package com.faith.pay.payment;

import com.faith.pay.error.PaymentNotFoundException;
import com.faith.pay.payment.audit.*;
import com.faith.pay.reconciliation.ReconciliationAttemptStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentRecoveryService {
    public enum TransitionResult { CHANGED, ALREADY_CORRECT, CONTRADICTORY }
    private final PaymentRepository payments;
    private final PaymentEventStore events;
    private final ReconciliationAttemptStore attempts;

    public PaymentRecoveryService(PaymentRepository payments, PaymentEventStore events,
                                  ReconciliationAttemptStore attempts) {
        this.payments = payments; this.events = events; this.attempts = attempts;
    }

    @Transactional
    public TransitionResult markFailed(String reference) { return transition(reference, false); }
    @Transactional
    public TransitionResult markAbandoned(String reference) { return transition(reference, true); }

    private TransitionResult transition(String reference, boolean abandoned) {
        Payment payment = payments.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new PaymentNotFoundException(reference));
        if (!payment.getStatus().isOpen()) {
            if ((!abandoned && payment.getStatus() == PaymentStatus.FAILED)
                    || (abandoned && payment.getStatus() == PaymentStatus.ABANDONED)) {
                return TransitionResult.ALREADY_CORRECT;
            }
            payment.requireManualReview();
            events.append(payment, PaymentEventType.RECONCILIATION_MISMATCH,
                    payment.getStatus(), payment.getStatus(), PaymentEventSource.RECONCILIATION, null);
            attempts.append("PAYMENT", reference, "MANUAL_REVIEW", null,
                    "Provider terminal state contradicted local " + payment.getStatus());
            return TransitionResult.CONTRADICTORY;
        }
        PaymentStatus previous = payment.getStatus();
        if (abandoned) payment.markAbandoned(); else payment.markFailed();
        events.append(payment, abandoned ? PaymentEventType.PAYMENT_ABANDONED : PaymentEventType.PAYMENT_FAILED,
                previous, payment.getStatus(), PaymentEventSource.RECONCILIATION, null);
        return TransitionResult.CHANGED;
    }

    @Transactional
    public void recordAttempt(String reference, String outcome, String providerStatus, String details,
                              int maxAttempts, boolean unresolved) {
        Payment payment = payments.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new PaymentNotFoundException(reference));
        payment.recordReconciliationAttempt();
        attempts.append("PAYMENT", reference, outcome, providerStatus, details);
        if (unresolved && payment.getReconciliationAttempts() >= maxAttempts && !payment.isManualReviewRequired()) {
            payment.requireManualReview();
            attempts.append("PAYMENT", reference, "MANUAL_REVIEW", providerStatus,
                    "Maximum reconciliation attempts reached: " + maxAttempts);
        }
    }

    @Transactional
    public void flagManualReview(String reference, String providerStatus, String details) {
        Payment payment = payments.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new PaymentNotFoundException(reference));
        payment.recordReconciliationAttempt();
        payment.requireManualReview();
        attempts.append("PAYMENT", reference, "MANUAL_REVIEW", providerStatus, details);
        events.append(payment, PaymentEventType.RECONCILIATION_MISMATCH, payment.getStatus(), payment.getStatus(),
                PaymentEventSource.RECONCILIATION, null);
    }
}
