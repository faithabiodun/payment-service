package com.faith.pay.reconciliation;

import com.faith.pay.payment.*;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.PaystackClient;
import com.faith.pay.provider.dto.PaystackTransactionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;

@Service
public class PaymentReconciler {
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);
    private final PaystackClient paystack;
    private final PaymentSettlementService settlement;
    private final PaymentRecoveryService recovery;
    private final PaymentRepository payments;
    private final ReconciliationProperties properties;

    public PaymentReconciler(PaystackClient paystack, PaymentSettlementService settlement,
                             PaymentRecoveryService recovery, PaymentRepository payments,
                             ReconciliationProperties properties) {
        this.paystack = paystack; this.settlement = settlement; this.recovery = recovery;
        this.payments = payments; this.properties = properties;
    }

    public void reconcile(Payment candidate) {
        String reference = candidate.getReference();
        log.info("Reconciliation checking payment {}", reference);
        PaystackTransactionData provider;
        try {
            provider = paystack.verify(reference);
        } catch (RuntimeException unavailable) {
            log.warn("Provider unavailable while reconciling payment {}", reference, unavailable);
            recovery.recordAttempt(reference, "PROVIDER_UNAVAILABLE", null,
                    safeDetail(unavailable), properties.maxAttempts(), true);
            return;
        }

        String status = provider.status() == null ? "" : provider.status().toLowerCase(Locale.ROOT);
        switch (status) {
            case "success" -> {
                try {
                    SettlementResult result = settlement.settleSuccess(reference, provider,
                            PaymentEventSource.RECONCILIATION, null);
                    String outcome = result == SettlementResult.APPLIED ? "RECOVERED" : "ALREADY_CORRECT";
                    recovery.recordAttempt(reference, outcome, provider.status(), "Provider success synchronized",
                            properties.maxAttempts(), false);
                    log.info("Reconciliation recovered payment {} from {} to SUCCESS", reference, candidate.getStatus());
                } catch (RuntimeException contradiction) {
                    recovery.flagManualReview(reference, provider.status(), safeDetail(contradiction));
                    log.warn("Payment {} requires manual review after provider success contradicted local state", reference);
                }
            }
            case "failed" -> {
                PaymentRecoveryService.TransitionResult result = recovery.markFailed(reference);
                if (result != PaymentRecoveryService.TransitionResult.CONTRADICTORY) {
                    recovery.recordAttempt(reference,
                            result == PaymentRecoveryService.TransitionResult.CHANGED ? "MARKED_FAILED" : "ALREADY_CORRECT",
                            provider.status(), result == PaymentRecoveryService.TransitionResult.CHANGED
                                    ? "Provider reports failed" : "Local payment was already failed",
                            properties.maxAttempts(), false);
                }
            }
            case "abandoned" -> {
                if (candidate.getCreatedAt() != null && candidate.getCreatedAt().isBefore(Instant.now().minus(properties.abandonAfter()))) {
                    PaymentRecoveryService.TransitionResult result = recovery.markAbandoned(reference);
                    if (result != PaymentRecoveryService.TransitionResult.CONTRADICTORY) {
                        recovery.recordAttempt(reference,
                                result == PaymentRecoveryService.TransitionResult.CHANGED ? "MARKED_ABANDONED" : "ALREADY_CORRECT",
                                provider.status(), "Provider reports abandoned", properties.maxAttempts(), false);
                    }
                } else {
                    recovery.recordAttempt(reference, "STILL_OPEN", provider.status(),
                            "Abandoned provider transaction is younger than configured threshold",
                            properties.maxAttempts(), true);
                }
            }
            case "pending", "processing", "ongoing", "queued" -> recovery.recordAttempt(reference,
                    "STILL_OPEN", provider.status(), "Provider transaction remains open", properties.maxAttempts(), true);
            default -> {
                recovery.flagManualReview(reference, provider.status(), "Unsupported or ambiguous provider payment status");
                log.warn("Payment {} requires manual review for provider status {}", reference, provider.status());
            }
        }
    }

    private String safeDetail(RuntimeException failure) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) return failure.getClass().getSimpleName();
        return detail.length() > 480 ? detail.substring(0, 480) : detail;
    }
}
