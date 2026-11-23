package com.faith.paymentservice;

import com.faith.pay.error.*;
import com.faith.pay.order.Order;
import com.faith.pay.payment.*;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.payment.dto.InitializePaymentRequest;
import com.faith.pay.provider.core.*;
import com.faith.pay.refund.*;
import com.faith.pay.refund.dto.RefundRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentAndRefundServiceTests {

    @Test
    void paymentInitializeServesReplayBeforeRoutingRejectsPendingAndHandlesRace() throws Exception {
        var commands = mock(PaymentCommandService.class);
        var paymentRepo = mock(PaymentRepository.class);
        var registry = mock(PaymentProviderRegistry.class);
        var routing = mock(ProviderRoutingPolicy.class);
        var settlement = mock(PaymentSettlementService.class);
        var service = new PaymentService(commands, paymentRepo, registry, routing, settlement);

        Order order = new Order("buyer@example.test", 100_000L);
        Field idField = Order.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(order, 42L);

        Payment processing = new Payment("PAY-1", order, 100_000L, "key-processing", PaymentProviderType.PAYSTACK);
        processing.markProcessing("https://pay.test/1", "acc_1");
        when(paymentRepo.findByIdempotencyKey("key-processing")).thenReturn(Optional.of(processing));

        var replay = service.initialize(new InitializePaymentRequest(42L, null), "key-processing");
        assertThat(replay.reference()).isEqualTo("PAY-1");
        assertThat(replay.authorizationUrl()).isEqualTo("https://pay.test/1");
        verifyNoInteractions(routing, registry, commands);

        Payment pending = new Payment("PAY-2", order, 100_000L, "key-pending", PaymentProviderType.PAYSTACK);
        when(paymentRepo.findByIdempotencyKey("key-pending")).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> service.initialize(new InitializePaymentRequest(42L, null), "key-pending"))
                .isInstanceOf(PaymentInitializationInProgressException.class);

        assertThatThrownBy(() -> service.initialize(new InitializePaymentRequest(42L, null), "k".repeat(101)))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));

        Order otherOrder = new Order("other@example.test", 100_000L);
        idField.set(otherOrder, 99L);
        Payment otherPayment = new Payment("PAY-OTHER", otherOrder, 100_000L, "key-race", PaymentProviderType.PAYSTACK);
        otherPayment.markProcessing("https://pay.test/other", "acc_other");
        var mockProvider = mock(PaymentProvider.class);
        when(routing.choose(null)).thenReturn(PaymentProviderType.PAYSTACK);
        when(registry.get(PaymentProviderType.PAYSTACK)).thenReturn(mockProvider);
        when(paymentRepo.findByIdempotencyKey("key-race")).thenReturn(Optional.empty(), Optional.of(otherPayment));
        when(commands.reserve(42L, "key-race", PaymentProviderType.PAYSTACK))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        assertThatThrownBy(() -> service.initialize(new InitializePaymentRequest(42L, null), "key-race"))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void refundServiceThrowsNotFoundAndUsesFreshLockedProviderTransactionId() {
        var commands = mock(RefundCommandService.class);
        var states = mock(RefundStateService.class);
        var refundRepo = mock(RefundRepository.class);
        var paymentRepo = mock(PaymentRepository.class);
        var providerRegistry = mock(RefundProviderRegistry.class);
        var provider = mock(RefundProvider.class);
        var service = new RefundService(commands, states, refundRepo, paymentRepo, providerRegistry);

        when(refundRepo.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(paymentRepo.findByReference("PAY-MISSING")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refund("PAY-MISSING", new RefundRequest(100L, "test"), "key-missing"))
                .isInstanceOf(PaymentNotFoundException.class);

        Order order = new Order("buyer@example.test", 500_000L);
        Payment stalePayment = new Payment("PAY-STRIPE-1", order, 500_000L, "pay-key", PaymentProviderType.STRIPE);
        when(paymentRepo.findByReference("PAY-STRIPE-1")).thenReturn(Optional.of(stalePayment));
        when(providerRegistry.get(PaymentProviderType.STRIPE)).thenReturn(provider);
        when(commands.reserve(eq("PAY-STRIPE-1"), any(), eq("key-fresh"))).thenReturn(
                new RefundReservation(1L, "REF-1", "PAY-STRIPE-1", PaymentProviderType.STRIPE,
                        "pi_fresh_123", "NGN", 100_000L, RefundStatus.PENDING, null, true));
        when(provider.refund(any())).thenReturn(new ProviderRefundResult(PaymentProviderType.STRIPE,
                "re_123", "REF-1", ProviderRefundState.SUCCEEDED, 100_000L, "NGN", "succeeded"));
        Refund applied = new Refund(stalePayment, "REF-1", 100_000L, 100_000L, "test", "key-fresh");
        applied.markSuccessful("re_123", "succeeded");
        when(states.applyVerifiedState(eq("REF-1"), any(), eq(PaymentEventSource.REFUND), isNull())).thenReturn(applied);

        var response = service.refund("PAY-STRIPE-1", new RefundRequest(100_000L, "test"), "key-fresh");

        assertThat(response.providerRefundId()).isEqualTo("re_123");
        verify(provider).refund(argThat(req -> "pi_fresh_123".equals(req.providerPaymentId())));
    }

    @Test
    void paymentEntityHandlesNullFeesKoboSafely() throws Exception {
        Payment payment = new Payment("PAY-NULL-FEE", new Order("a@b.test", 1000L), 1000L, "key");
        Field feesField = Payment.class.getDeclaredField("feesKobo");
        feesField.setAccessible(true);
        feesField.set(payment, null);

        assertThat(payment.getFeesKobo()).isZero();
    }
}
