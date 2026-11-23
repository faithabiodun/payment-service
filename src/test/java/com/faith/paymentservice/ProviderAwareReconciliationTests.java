package com.faith.paymentservice;

import com.faith.pay.payment.*;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.core.*;
import com.faith.pay.reconciliation.*;
import com.faith.pay.refund.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.*;

class ProviderAwareReconciliationTests {
    @Test
    void paymentReconciliationQueriesOnlyThePersistedProvider() {
        var registry = mock(PaymentProviderRegistry.class);
        var provider = mock(PaymentProvider.class);
        var settlement = mock(PaymentSettlementService.class);
        var recovery = mock(PaymentRecoveryService.class);
        var properties = new ReconciliationProperties(java.time.Duration.ofMinutes(10),
                java.time.Duration.ofHours(24), java.time.Duration.ofMinutes(10), 5, 100);
        var reconciler = new PaymentReconciler(registry, settlement, recovery, properties);
        var payment = mock(Payment.class);
        when(payment.getReference()).thenReturn("PAY-STRIPE");
        when(payment.getProvider()).thenReturn(PaymentProviderType.STRIPE);
        when(payment.getProviderTransactionId()).thenReturn(null);
        when(payment.getAccessCode()).thenReturn("cs_stripe_session");
        when(payment.getStatus()).thenReturn(PaymentStatus.PROCESSING);
        when(payment.getCreatedAt()).thenReturn(Instant.now());
        var verified = new VerifiedPayment(PaymentProviderType.STRIPE, "pi_stripe", "PAY-STRIPE",
                ProviderPaymentState.PROCESSING, 100_000, "NGN", null, null, null);
        when(registry.get(PaymentProviderType.STRIPE)).thenReturn(provider);
        when(provider.verify("PAY-STRIPE", "cs_stripe_session")).thenReturn(verified);

        reconciler.reconcile(payment);

        verify(registry).get(PaymentProviderType.STRIPE);
        verify(provider).verify("PAY-STRIPE", "cs_stripe_session");
        verify(recovery).recordAttempt("PAY-STRIPE", "STILL_OPEN", "PROCESSING",
                "Provider transaction remains open", 5, true);
        verifyNoInteractions(settlement);
    }

    @Test
    void refundReconciliationQueriesOnlyThePersistedProvider() {
        var registry = mock(RefundProviderRegistry.class);
        var provider = mock(RefundProvider.class);
        var states = mock(RefundStateService.class);
        var recovery = mock(RefundRecoveryService.class);
        var properties = new ReconciliationProperties(java.time.Duration.ofMinutes(10),
                java.time.Duration.ofHours(24), java.time.Duration.ofMinutes(10), 5, 100);
        var reconciler = new RefundReconciler(states, recovery, properties, registry);
        var refund = mock(Refund.class);
        var payment = mock(Payment.class);
        when(refund.getReference()).thenReturn("REF-FLW");
        when(refund.getProvider()).thenReturn(PaymentProviderType.FLUTTERWAVE);
        when(refund.getProviderRefundId()).thenReturn("flw_refund_1");
        when(refund.getPayment()).thenReturn(payment);
        when(payment.getCurrency()).thenReturn("NGN");
        var verified = new VerifiedRefund(PaymentProviderType.FLUTTERWAVE, "flw_refund_1", "REF-FLW",
                ProviderRefundState.PROCESSING, 25_000, "NGN", "processing");
        when(registry.get(PaymentProviderType.FLUTTERWAVE)).thenReturn(provider);
        when(provider.verifyRefund("flw_refund_1", "NGN")).thenReturn(verified);

        reconciler.reconcile(refund);

        verify(registry).get(PaymentProviderType.FLUTTERWAVE);
        verify(provider).verifyRefund("flw_refund_1", "NGN");
        verify(states).applyVerifiedState("REF-FLW", verified, PaymentEventSource.RECONCILIATION, null);
        verify(recovery).recordAttempt("REF-FLW", "SYNCHRONIZED", "processing",
                "Provider refund state synchronized", 5, true);
    }

    @Test
    void youngCancelledPaymentDoesNotTripMaxAttemptsAndUnavailableProviderRetries() {
        var registry = mock(PaymentProviderRegistry.class);
        var provider = mock(PaymentProvider.class);
        var settlement = mock(PaymentSettlementService.class);
        var recovery = mock(PaymentRecoveryService.class);
        var properties = new ReconciliationProperties(java.time.Duration.ofMinutes(10),
                java.time.Duration.ofHours(24), java.time.Duration.ofMinutes(10), 5, 100);
        var reconciler = new PaymentReconciler(registry, settlement, recovery, properties);
        var payment = mock(Payment.class);
        when(payment.getReference()).thenReturn("PAY-CANCELLED");
        when(payment.getProvider()).thenReturn(PaymentProviderType.PAYSTACK);
        when(payment.getStatus()).thenReturn(PaymentStatus.PROCESSING);
        when(payment.getCreatedAt()).thenReturn(Instant.now().minus(java.time.Duration.ofHours(1)));
        when(registry.get(PaymentProviderType.PAYSTACK)).thenReturn(provider);
        when(provider.verify("PAY-CANCELLED", null)).thenReturn(new VerifiedPayment(
                PaymentProviderType.PAYSTACK, "11", "PAY-CANCELLED", ProviderPaymentState.CANCELLED,
                100_000L, "NGN", null, null, null));

        reconciler.reconcile(payment);

        verify(recovery).recordAttempt(eq("PAY-CANCELLED"), eq("STILL_OPEN"), eq("CANCELLED"),
                anyString(), eq(5), eq(false));

        when(provider.verify("PAY-CANCELLED", null)).thenThrow(new com.faith.pay.error.AppException(
                com.faith.pay.error.ErrorCode.PROVIDER_UNAVAILABLE,
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "not configured"));
        reconciler.reconcile(payment);

        verify(recovery).recordAttempt(eq("PAY-CANCELLED"), eq("PROVIDER_UNAVAILABLE"), isNull(),
                contains("not configured"), eq(5), eq(true));
        verify(recovery, never()).flagManualReview(anyString(), any(), any(), anyString());
    }

    @Test
    void refundRecoveryLocksPaymentBeforeRefundAndSkipsTerminalEscalation() {
        var refundRepo = mock(RefundRepository.class);
        var paymentRepo = mock(PaymentRepository.class);
        var attempts = mock(ReconciliationAttemptStore.class);
        var recovery = new RefundRecoveryService(refundRepo, paymentRepo, attempts);

        var payment = new Payment("PAY-1", new com.faith.pay.order.Order("a@b.test", 500_000L), 500_000L, "pk");
        var refund = new Refund(payment, "REF-1", 100_000L, 100_000L, "test", "rk");
        refund.markSuccessful("9001", "processed");

        when(refundRepo.findPaymentReferenceByReference("REF-1")).thenReturn(java.util.Optional.of("PAY-1"));
        when(paymentRepo.findByReferenceForUpdate("PAY-1")).thenReturn(java.util.Optional.of(payment));
        when(refundRepo.findByReferenceForUpdate("REF-1")).thenReturn(java.util.Optional.of(refund));

        recovery.recordAttempt("REF-1", "PROVIDER_UNAVAILABLE", null, "timeout", 1, true);

        var order = inOrder(paymentRepo, refundRepo);
        order.verify(paymentRepo).findByReferenceForUpdate("PAY-1");
        order.verify(refundRepo).findByReferenceForUpdate("REF-1");
        org.assertj.core.api.Assertions.assertThat(refund.isManualReviewRequired()).isFalse();
        verify(attempts).append(eq("REFUND"), eq("REF-1"), eq("ALREADY_CORRECT"), isNull(), contains("SUCCESS"));
    }
}
