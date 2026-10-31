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
                "Provider refund state synchronized", 5, false);
    }
}
