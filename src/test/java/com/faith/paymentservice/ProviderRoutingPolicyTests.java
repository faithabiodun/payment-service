package com.faith.paymentservice;

import com.faith.pay.provider.core.ConfiguredProviderRoutingPolicy;
import com.faith.pay.provider.core.PaymentProviderType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderRoutingPolicyTests {
    @Test
    void explicitProviderTakesPrecedenceOverConfiguredDefault() {
        var policy = new ConfiguredProviderRoutingPolicy(PaymentProviderType.PAYSTACK);

        assertThat(policy.choose(PaymentProviderType.STRIPE)).isEqualTo(PaymentProviderType.STRIPE);
        assertThat(policy.choose(PaymentProviderType.FLUTTERWAVE)).isEqualTo(PaymentProviderType.FLUTTERWAVE);
    }

    @Test
    void missingProviderUsesConfiguredDefault() {
        var policy = new ConfiguredProviderRoutingPolicy(PaymentProviderType.STRIPE);

        assertThat(policy.choose(null)).isEqualTo(PaymentProviderType.STRIPE);
    }
}
