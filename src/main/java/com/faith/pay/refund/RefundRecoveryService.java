package com.faith.pay.refund;

import com.faith.pay.reconciliation.ReconciliationAttemptStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundRecoveryService {
    private final RefundRepository refunds;
    private final ReconciliationAttemptStore attempts;
    public RefundRecoveryService(RefundRepository refunds, ReconciliationAttemptStore attempts) {
        this.refunds = refunds; this.attempts = attempts;
    }

    @Transactional
    public void recordAttempt(String reference, String outcome, String providerStatus, String details,
                              int maxAttempts, boolean unresolved) {
        Refund refund = refunds.findByReferenceForUpdate(reference).orElseThrow();
        refund.recordReconciliationAttempt();
        attempts.append("REFUND", reference, outcome, providerStatus, details);
        if (unresolved && refund.getReconciliationAttempts() >= maxAttempts && !refund.isManualReviewRequired()) {
            refund.requireManualReview();
            attempts.append("REFUND", reference, "MANUAL_REVIEW", providerStatus,
                    "Maximum reconciliation attempts reached: " + maxAttempts);
        }
    }

    @Transactional
    public void flagManualReview(String reference, String providerStatus, String details) {
        Refund refund = refunds.findByReferenceForUpdate(reference).orElseThrow();
        refund.recordReconciliationAttempt();
        refund.requireManualReview();
        attempts.append("REFUND", reference, "MANUAL_REVIEW", providerStatus, details);
    }
}
