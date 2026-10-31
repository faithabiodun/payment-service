package com.faith.pay.reconciliation;

import com.faith.pay.error.ProviderCommunicationException;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.core.RefundProviderRegistry;
import com.faith.pay.provider.core.VerifiedRefund;
import com.faith.pay.refund.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/** Resolves refunds only through the provider saved on the refund row. */
@Service
public class RefundReconciler {
    private static final Logger log = LoggerFactory.getLogger(RefundReconciler.class);
    private final RefundStateService states;
    private final RefundRecoveryService recovery;
    private final ReconciliationProperties properties;
    private final RefundProviderRegistry providers;

    public RefundReconciler(RefundStateService states, RefundRecoveryService recovery,
                            ReconciliationProperties properties, RefundProviderRegistry providers) {
        this.states = states;
        this.recovery = recovery;
        this.properties = properties;
        this.providers = providers;
    }

    public void reconcile(Refund refund) {
        String reference = refund.getReference();
        var provider = providers.get(refund.getProvider());
        String currency = refund.getPayment().getCurrency();
        log.info("Reconciliation checking refund reference={}, provider={}", reference, refund.getProvider());

        try {
            if (refund.getProviderRefundId() != null && !refund.getProviderRefundId().isBlank()) {
                VerifiedRefund verified = provider.verifyRefund(refund.getProviderRefundId(), currency);
                states.applyVerifiedState(reference, verified, PaymentEventSource.RECONCILIATION, null);
                recovery.recordAttempt(reference, "SYNCHRONIZED", verified.providerStatus(),
                        "Provider refund state synchronized", properties.maxAttempts(), false);
                return;
            }

            String providerPaymentId = refund.getPayment().getProviderTransactionId();
            if (providerPaymentId == null || providerPaymentId.isBlank()) {
                recovery.recordAttempt(reference, "PROVIDER_RESULT_UNKNOWN", null,
                        "Payment has no provider transaction identity for refund discovery",
                        properties.maxAttempts(), true);
                return;
            }

            List<VerifiedRefund> matches = provider.findRefundsForPayment(providerPaymentId, reference, currency);
            if (matches.size() > 1) {
                recovery.flagManualReview(reference, null,
                        ReconciliationDiscrepancyType.DUPLICATE_PROVIDER_TRANSACTION,
                        "Multiple provider refunds match the local refund reference");
            } else if (matches.isEmpty()) {
                recovery.recordAttempt(reference, "PROVIDER_RESULT_UNKNOWN", null,
                        "No provider refund matches the local reference yet; the refund was not resubmitted",
                        properties.maxAttempts(), true);
            } else {
                VerifiedRefund verified = matches.getFirst();
                states.applyVerifiedState(reference, verified, PaymentEventSource.RECONCILIATION, null);
                recovery.recordAttempt(reference, "RECOVERED", verified.providerStatus(),
                        "Recovered provider refund using its local reference marker",
                        properties.maxAttempts(), false);
            }
        } catch (ProviderCommunicationException unavailable) {
            recovery.recordAttempt(reference, "PROVIDER_UNAVAILABLE", null, safeDetail(unavailable),
                    properties.maxAttempts(), true);
            log.warn("Provider unavailable while reconciling refund reference={}, provider={}, errorType={}",
                    reference, refund.getProvider(), unavailable.getClass().getSimpleName());
        } catch (RuntimeException discrepancy) {
            recovery.flagManualReview(reference, null, classify(discrepancy), safeDetail(discrepancy));
            log.warn("Refund requires manual review: reference={}, discrepancy={}", reference, classify(discrepancy));
        }
    }

    private ReconciliationDiscrepancyType classify(RuntimeException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (message.contains("amount")) return ReconciliationDiscrepancyType.AMOUNT_MISMATCH;
        if (message.contains("currency")) return ReconciliationDiscrepancyType.CURRENCY_MISMATCH;
        if (message.contains("provider")) return ReconciliationDiscrepancyType.PROVIDER_MISMATCH;
        if (message.contains("transition") || message.contains("terminal")) {
            return ReconciliationDiscrepancyType.LOCAL_PROVIDER_CONTRADICTION;
        }
        return ReconciliationDiscrepancyType.PROVIDER_DATA_MISMATCH;
    }

    private String safeDetail(RuntimeException failure) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) return failure.getClass().getSimpleName();
        return detail.length() > 480 ? detail.substring(0, 480) : detail;
    }
}
