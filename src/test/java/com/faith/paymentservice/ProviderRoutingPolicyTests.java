package com.faith.paymentservice;

import com.faith.pay.provider.core.ConfiguredProviderRoutingPolicy;
import com.faith.pay.provider.core.PaymentProviderType;
import com.faith.pay.error.AppException;
import com.faith.pay.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderRoutingPolicyTests {
    @Test
    void explicitProviderTakesPrecedenceOverConfiguredDefault() {
        var policy = new ConfiguredProviderRoutingPolicy(PaymentProviderType.PAYSTACK);

        assertThat(policy.choose(PaymentProviderType.STRIPE)).isEqualTo(PaymentProviderType.STRIPE);
        assertThat(policy.choose(PaymentProviderType.PAYSTACK)).isEqualTo(PaymentProviderType.PAYSTACK);
    }

    @Test
    void missingProviderUsesConfiguredDefault() {
        var policy = new ConfiguredProviderRoutingPolicy(PaymentProviderType.STRIPE);

        assertThat(policy.choose(null)).isEqualTo(PaymentProviderType.STRIPE);
    }

    @Test
    void decommissionedFlutterwaveIsRejectedWithoutFallback() {
        var policy = new ConfiguredProviderRoutingPolicy(PaymentProviderType.PAYSTACK);

        assertThatThrownBy(() -> policy.choose(PaymentProviderType.FLUTTERWAVE))
                .isInstanceOf(AppException.class)
                .hasMessage("Flutterwave is no longer available for new payment attempts")
                .satisfies(error -> assertThat(((AppException) error).getCode())
                        .isEqualTo(ErrorCode.PROVIDER_NOT_AVAILABLE));
    }
}
