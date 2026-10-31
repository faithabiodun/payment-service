package com.faith.pay.reconciliation;

import com.faith.pay.error.*;
import com.faith.pay.payment.*;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Queries only the provider persisted on this payment attempt, then applies neutral domain results. */
@Service
public class PaymentReconciler {
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);
    private final PaymentProviderRegistry providers;
    private final PaymentSettlementService settlement;
    private final PaymentRecoveryService recovery;
    private final ReconciliationProperties properties;

    public PaymentReconciler(PaymentProviderRegistry providers, PaymentSettlementService settlement,
                             PaymentRecoveryService recovery, ReconciliationProperties properties) {
        this.providers = providers;
        this.settlement = settlement;
        this.recovery = recovery;
        this.properties = properties;
    }

    public void reconcile(Payment candidate) {
        String reference = candidate.getReference();
        PaymentProviderType owner = candidate.getProvider();
        log.info("Reconciliation checking payment reference={}, provider={}", reference, owner);

        String providerId = candidate.getProviderTransactionId();
        if (providerId == null || providerId.isBlank()) providerId = candidate.getAccessCode();
        final VerifiedPayment provider;
        try {
            provider = providers.get(owner).verify(reference, providerId);
        } catch (ProviderCommunicationException unavailable) {
            log.warn("Provider unavailable while reconciling payment reference={}, provider={}, errorType={}",
                    reference, owner, unavailable.getClass().getSimpleName());
            recovery.recordAttempt(reference, "PROVIDER_UNAVAILABLE", null,
                    safeDetail(unavailable), properties.maxAttempts(), true);
            return;
        } catch (RuntimeException discrepancy) {
            recovery.flagManualReview(reference, null, classify(discrepancy), safeDetail(discrepancy));
            return;
        }

        if (provider == null || provider.provider() != owner) {
            recovery.flagManualReview(reference, null, ReconciliationDiscrepancyType.PROVIDER_MISMATCH,
                    "Verification result did not come from the provider owning this payment");
            return;
        }

        switch (provider.state()) {
            case SUCCEEDED -> recoverSuccess(candidate, provider);
            case FAILED -> reconcileFailed(candidate, provider);
            case CANCELLED -> reconcileCancelled(candidate, provider);
            case PENDING, PROCESSING, REQUIRES_ACTION -> {
                if (candidate.getStatus().hasSuccessfulCharge()) {
                    recovery.flagManualReview(reference, provider.state().name(),
                            ReconciliationDiscrepancyType.LOCAL_PROVIDER_CONTRADICTION,
                            "Local payment is successful while the owning provider reports " + provider.state());
                } else {
                    recovery.recordAttempt(reference, "STILL_OPEN", provider.state().name(),
                            "Provider transaction remains open", properties.maxAttempts(), true);
                }
            }
            case UNKNOWN -> recovery.flagManualReview(reference, "UNKNOWN",
                    ReconciliationDiscrepancyType.UNKNOWN_PROVIDER_STATE,
                    "Provider returned an unknown payment state");
        }
    }

    private void recoverSuccess(Payment candidate, VerifiedPayment provider) {
        if (!candidate.getStatus().isOpen() && !candidate.getStatus().hasSuccessfulCharge()) {
            recovery.flagManualReview(candidate.getReference(), provider.state().name(),
                    ReconciliationDiscrepancyType.LOCAL_PROVIDER_CONTRADICTION,
                    "Provider reports success while local payment is terminal " + candidate.getStatus());
            return;
        }
        try {
            SettlementResult result = settlement.settleSuccess(candidate.getReference(), provider,
                    PaymentEventSource.RECONCILIATION, null);
            String outcome = result == SettlementResult.APPLIED ? "RECOVERED" : "ALREADY_CORRECT";
            recovery.recordAttempt(candidate.getReference(), outcome, provider.state().name(),
                    "Provider success synchronized", properties.maxAttempts(), false);
            log.info("Reconciliation synchronized payment reference={}, provider={}, result={}",
                    candidate.getReference(), candidate.getProvider(), outcome);
        } catch (RuntimeException contradiction) {
            recovery.flagManualReview(candidate.getReference(), provider.state().name(), classify(contradiction),
                    safeDetail(contradiction));
            log.warn("Payment requires manual review: reference={}, discrepancy={}",
                    candidate.getReference(), classify(contradiction));
        }
    }

    private void reconcileFailed(Payment candidate, VerifiedPayment provider) {
        if (candidate.getStatus().hasSuccessfulCharge()) {
            recovery.flagManualReview(candidate.getReference(), provider.state().name(),
                    ReconciliationDiscrepancyType.LOCAL_PROVIDER_CONTRADICTION,
                    "Local payment is successful while provider reports failure");
            return;
        }
        PaymentRecoveryService.TransitionResult result = recovery.markFailed(candidate.getReference());
        if (result == PaymentRecoveryService.TransitionResult.CONTRADICTORY) return;
        recovery.recordAttempt(candidate.getReference(),
                result == PaymentRecoveryService.TransitionResult.CHANGED ? "MARKED_FAILED" : "ALREADY_CORRECT",
                provider.state().name(), result == PaymentRecoveryService.TransitionResult.CHANGED
                        ? "Provider reports failure" : "Local payment was already failed",
                properties.maxAttempts(), false);
    }

    private void reconcileCancelled(Payment candidate, VerifiedPayment provider) {
        if (candidate.getStatus().hasSuccessfulCharge()) {
            recovery.flagManualReview(candidate.getReference(), provider.state().name(),
                    ReconciliationDiscrepancyType.LOCAL_PROVIDER_CONTRADICTION,
                    "Local payment is successful while provider reports cancellation");
            return;
        }
        if (candidate.getCreatedAt() != null
                && candidate.getCreatedAt().isBefore(Instant.now().minus(properties.abandonAfter()))) {
            PaymentRecoveryService.TransitionResult result = recovery.markAbandoned(candidate.getReference());
            if (result == PaymentRecoveryService.TransitionResult.CONTRADICTORY) return;
            recovery.recordAttempt(candidate.getReference(),
                    result == PaymentRecoveryService.TransitionResult.CHANGED ? "MARKED_ABANDONED" : "ALREADY_CORRECT",
                    provider.state().name(), "Provider reports cancellation", properties.maxAttempts(), false);
        } else {
            recovery.recordAttempt(candidate.getReference(), "STILL_OPEN", provider.state().name(),
                    "Cancelled provider transaction is younger than configured threshold",
                    properties.maxAttempts(), true);
        }
    }

    private ReconciliationDiscrepancyType classify(RuntimeException failure) {
        if (failure instanceof PaymentAmountMismatchException) return ReconciliationDiscrepancyType.AMOUNT_MISMATCH;
        if (failure instanceof PaymentCurrencyMismatchException) return ReconciliationDiscrepancyType.CURRENCY_MISMATCH;
        if (failure instanceof PaymentReferenceMismatchException) return ReconciliationDiscrepancyType.REFERENCE_MISMATCH;
        if (failure.getMessage() != null && failure.getMessage().contains("provider")) {
            return ReconciliationDiscrepancyType.PROVIDER_MISMATCH;
        }
        return ReconciliationDiscrepancyType.PROVIDER_DATA_MISMATCH;
    }

    private String safeDetail(RuntimeException failure) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) return failure.getClass().getSimpleName();
        return detail.length() > 480 ? detail.substring(0, 480) : detail;
    }
}
