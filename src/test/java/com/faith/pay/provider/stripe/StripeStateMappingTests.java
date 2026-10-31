package com.faith.pay.provider.stripe;

import com.faith.pay.provider.core.ProviderPaymentState;
import com.faith.pay.provider.core.ProviderRefundState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StripeStateMappingTests {
    @Test
    void preservesStripePaymentLifecycleIncludingActionAndUnknown() {
        assertThat(StripePaymentProvider.paymentState("requires_action")).isEqualTo(ProviderPaymentState.REQUIRES_ACTION);
        assertThat(StripePaymentProvider.paymentState("processing")).isEqualTo(ProviderPaymentState.PROCESSING);
        assertThat(StripePaymentProvider.paymentState("succeeded")).isEqualTo(ProviderPaymentState.SUCCEEDED);
        assertThat(StripePaymentProvider.paymentState("canceled")).isEqualTo(ProviderPaymentState.CANCELLED);
        assertThat(StripePaymentProvider.paymentState("future_status")).isEqualTo(ProviderPaymentState.UNKNOWN);
    }

    @Test
    void mapsStripeRefundStatesWithoutTurningUnknownIntoFailure() {
        assertThat(StripePaymentProvider.refundState("pending")).isEqualTo(ProviderRefundState.PENDING);
        assertThat(StripePaymentProvider.refundState("succeeded")).isEqualTo(ProviderRefundState.SUCCEEDED);
        assertThat(StripePaymentProvider.refundState("failed")).isEqualTo(ProviderRefundState.FAILED);
        assertThat(StripePaymentProvider.refundState("future_status")).isEqualTo(ProviderRefundState.UNKNOWN);
    }
}
