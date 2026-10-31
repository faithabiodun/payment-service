package com.faith.pay.reconciliation;

import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.PaystackClient;
import com.faith.pay.provider.core.PaymentProviderType;
import com.faith.pay.provider.core.RefundProviderRegistry;
import com.faith.pay.provider.core.VerifiedRefund;
import com.faith.pay.provider.dto.PaystackRefundData;
import com.faith.pay.refund.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RefundReconciler {
    private static final Logger log = LoggerFactory.getLogger(RefundReconciler.class);
    private final PaystackClient paystack;
    private final RefundStateService states;
    private final RefundRecoveryService recovery;
    private final ReconciliationProperties properties;
    private final RefundProviderRegistry providers;

    public RefundReconciler(PaystackClient paystack, RefundStateService states,
                            RefundRecoveryService recovery, ReconciliationProperties properties,
                            RefundProviderRegistry providers) {
        this.paystack = paystack; this.states = states; this.recovery = recovery; this.properties = properties;
        this.providers = providers;
    }

    public void reconcile(Refund refund) {
        String reference = refund.getReference();
        log.info("Reconciliation checking refund {}", reference);
        if (refund.getProvider() != PaymentProviderType.PAYSTACK) {
            reconcileNonPaystack(refund);
            return;
        }
        try {
            if (refund.getProviderRefundId() != null) {
                PaystackRefundData provider = paystack.fetchRefund(refund.getProviderRefundId());
                states.applyProviderState(reference, provider, PaymentEventSource.RECONCILIATION, null);
                recovery.recordAttempt(reference, "SYNCHRONIZED", provider.status(),
                        "Provider refund state synchronized", properties.maxAttempts(), false);
                log.info("Refund {} synchronized to {}", reference, provider.status());
                return;
            }

            String transactionId = refund.getPayment().getProviderTransactionId();
            if (transactionId == null || transactionId.isBlank()) {
                recovery.recordAttempt(reference, "NOT_FOUND_YET", null,
                        "Payment has no provider transaction id for refund discovery", properties.maxAttempts(), true);
                return;
            }

            List<PaystackRefundData> found = paystack.listRefundsForTransaction(transactionId).stream()
                    .filter(item -> item.merchantNote() != null
                            && item.merchantNote().contains("Local refund: " + reference))
                    .toList();
            if (found.size() > 1) {
                recovery.flagManualReview(reference, null, "Multiple provider refunds match local refund marker");
                log.error("Refund {} requires manual review: multiple provider refund matches", reference);
            } else if (found.isEmpty()) {
                recovery.recordAttempt(reference, "NOT_FOUND_YET", null,
                        "No matching provider refund found; another discovery check is required",
                        properties.maxAttempts(), true);
            } else {
                PaystackRefundData provider = found.getFirst();
                states.applyProviderState(reference, provider, PaymentEventSource.RECONCILIATION, null);
                recovery.recordAttempt(reference, "RECOVERED", provider.status(),
                        "Recovered provider refund using local merchant note", properties.maxAttempts(), false);
                log.info("Reconciliation recovered refund {} from provider list", reference);
            }
        } catch (RuntimeException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith("Could not")) {
                recovery.recordAttempt(reference, "PROVIDER_UNAVAILABLE", null,
                        safeDetail(failure), properties.maxAttempts(), true);
                log.warn("Provider unavailable while reconciling refund {}", reference, failure);
            } else {
                recovery.flagManualReview(reference, null, safeDetail(failure));
                log.warn("Refund {} requires manual review during reconciliation", reference, failure);
            }
        }
    }

    private void reconcileNonPaystack(Refund refund) {
        String reference = refund.getReference();
        if (refund.getProviderRefundId() == null) {
            String providerPaymentId = refund.getPayment().getProviderTransactionId();
            if (providerPaymentId == null || providerPaymentId.isBlank()) {
                recovery.recordAttempt(reference, "PROVIDER_RESULT_UNKNOWN", null,
                        "Refund outcome is ambiguous and the payment has no provider transaction ID",
                        properties.maxAttempts(), true);
                return;
            }
            try {
                List<VerifiedRefund> found = providers.get(refund.getProvider()).findRefundsForPayment(
                        providerPaymentId, reference, refund.getPayment().getCurrency());
                if (found.size() == 1) {
                    VerifiedRefund provider = found.getFirst();
                    states.applyVerifiedState(reference, provider, PaymentEventSource.RECONCILIATION, null);
                    recovery.recordAttempt(reference, "RECOVERED", provider.providerStatus(),
                            "Recovered provider refund using the local refund marker",
                            properties.maxAttempts(), false);
                } else if (found.size() > 1) {
                    recovery.flagManualReview(reference, null,
                            "Multiple provider refunds match the local refund marker");
                } else {
                    recovery.recordAttempt(reference, "PROVIDER_RESULT_UNKNOWN", null,
                            "No matching provider refund found yet; the operation was not resubmitted",
                            properties.maxAttempts(), true);
                }
            } catch (RuntimeException failure) {
                recovery.recordAttempt(reference, "PROVIDER_UNAVAILABLE", null, safeDetail(failure),
                        properties.maxAttempts(), true);
            }
            return;
        }
        try {
            VerifiedRefund provider = providers.get(refund.getProvider())
                    .verifyRefund(refund.getProviderRefundId(), refund.getPayment().getCurrency());
            states.applyVerifiedState(reference, provider, PaymentEventSource.RECONCILIATION, null);
            recovery.recordAttempt(reference, "SYNCHRONIZED", provider.providerStatus(),
                    "Provider refund state synchronized", properties.maxAttempts(), false);
        } catch (RuntimeException failure) {
            recovery.recordAttempt(reference, "PROVIDER_UNAVAILABLE", null, safeDetail(failure),
                    properties.maxAttempts(), true);
            log.warn("Provider unavailable while reconciling refund {}", reference, failure);
        }
    }

    private String safeDetail(RuntimeException failure) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) return failure.getClass().getSimpleName();
        return detail.length() > 480 ? detail.substring(0, 480) : detail;
    }
}
