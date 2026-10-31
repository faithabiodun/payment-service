package com.faith.paymentservice;

import com.faith.pay.config.StripeProperties;
import com.faith.pay.error.AppException;
import com.faith.pay.payment.PaymentCommandService;
import com.faith.pay.payment.PaymentService;
import com.faith.pay.payment.PaymentSettlementService;
import com.faith.pay.payment.dto.InitializePaymentRequest;
import com.faith.pay.provider.core.PaymentProviderRegistry;
import com.faith.pay.provider.core.ConfiguredProviderRoutingPolicy;
import com.faith.pay.provider.core.PaymentProviderType;
import com.faith.pay.provider.stripe.StripePaymentProvider;
import com.stripe.StripeClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PaymentProviderReadinessTests {
    @Test
    void missingStripeCredentialsAreRejectedBeforePaymentReservation() {
        var commands = mock(PaymentCommandService.class);
        var registry = mock(PaymentProviderRegistry.class);
        var stripe = new StripePaymentProvider(
                StripeClient.builder().setApiKey("sk_test_not_used").build(),
                new StripeProperties("", "", "https://api.stripe.com", "http://localhost/success", "http://localhost/cancel"));
        var service = new PaymentService(commands, mock(com.faith.pay.payment.PaymentRepository.class), registry,
                new ConfiguredProviderRoutingPolicy(PaymentProviderType.PAYSTACK),
                mock(PaymentSettlementService.class));
        when(registry.get(PaymentProviderType.STRIPE)).thenReturn(stripe);

        assertThatThrownBy(() -> service.initialize(new InitializePaymentRequest(10L, PaymentProviderType.STRIPE),
                "operation-without-stripe-config"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("STRIPE_SECRET_KEY is not configured");

        verifyNoInteractions(commands);
    }
}
