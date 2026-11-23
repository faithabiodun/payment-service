package com.faith.pay.refund;

import com.faith.pay.error.AppException;
import com.faith.pay.error.ErrorCode;
import com.faith.pay.error.PaymentNotFoundException;
import com.faith.pay.payment.PaymentRepository;
import com.faith.pay.reconciliation.ReconciliationAttemptStore;
import com.faith.pay.reconciliation.ReconciliationDiscrepancyType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundRecoveryService {
    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final ReconciliationAttemptStore attempts;

    public RefundRecoveryService(RefundRepository refunds, PaymentRepository payments,
                                 ReconciliationAttemptStore attempts) {
        this.refunds = refunds;
        this.payments = payments;
        this.attempts = attempts;
    }

    @Transactional
    public void recordAttempt(String reference, String outcome, String providerStatus, String details,
                              int maxAttempts, boolean unresolved) {
        Refund refund = lockRefundInCanonicalOrder(reference);
        if (refund.getStatus().isTerminal() && unresolved) {
            refund.recordReconciliationAttempt();
            attempts.append("REFUND", reference, "ALREADY_CORRECT", providerStatus,
                    "Refund already reached terminal state " + refund.getStatus());
            return;
        }
        refund.recordReconciliationAttempt();
        attempts.append("REFUND", reference, outcome, providerStatus, details);
        if (unresolved && refund.getReconciliationAttempts() >= maxAttempts && !refund.isManualReviewRequired()) {
            refund.requireManualReview();
            attempts.append("REFUND", reference, "MANUAL_REVIEW", providerStatus,
                    ReconciliationDiscrepancyType.MANUAL_REVIEW_REQUIRED,
                    "Maximum reconciliation attempts reached: " + maxAttempts);
        }
    }

    @Transactional
    public void flagManualReview(String reference, String providerStatus, String details) {
        flagManualReview(reference, providerStatus, ReconciliationDiscrepancyType.MANUAL_REVIEW_REQUIRED, details);
    }

    @Transactional
    public void flagManualReview(String reference, String providerStatus,
                                 ReconciliationDiscrepancyType discrepancy, String details) {
        Refund refund = lockRefundInCanonicalOrder(reference);
        refund.recordReconciliationAttempt();
        refund.requireManualReview();
        attempts.append("REFUND", reference, "MANUAL_REVIEW", providerStatus, discrepancy, details);
    }

    /** Always lock payment before refund, matching RefundStateService to prevent AB-BA deadlocks. */
    private Refund lockRefundInCanonicalOrder(String refundReference) {
        String paymentReference = refunds.findPaymentReferenceByReference(refundReference)
                .orElseThrow(() -> refundNotFound(refundReference));
        payments.findByReferenceForUpdate(paymentReference)
                .orElseThrow(() -> new PaymentNotFoundException(paymentReference));
        return refunds.findByReferenceForUpdate(refundReference)
                .orElseThrow(() -> refundNotFound(refundReference));
    }

    private AppException refundNotFound(String reference) {
        return new AppException(ErrorCode.REFUND_NOT_FOUND, HttpStatus.NOT_FOUND,
                "Refund not found: " + reference);
    }
}
