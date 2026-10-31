package com.faith.pay.provider.flutterwave;

import com.faith.pay.provider.core.ProviderPaymentState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlutterwaveV3PaymentProviderMappingTests {
    @Test
    void mapsFlutterwaveV3StatusesWithoutTreatingUnknownAsFailure() {
        assertThat(FlutterwaveV3PaymentProvider.state("successful")).isEqualTo(ProviderPaymentState.SUCCEEDED);
        assertThat(FlutterwaveV3PaymentProvider.state("pending")).isEqualTo(ProviderPaymentState.PENDING);
        assertThat(FlutterwaveV3PaymentProvider.state("failed")).isEqualTo(ProviderPaymentState.FAILED);
        assertThat(FlutterwaveV3PaymentProvider.state("future_state")).isEqualTo(ProviderPaymentState.UNKNOWN);
    }
}
