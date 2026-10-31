package com.faith.pay.provider.paystack;

import com.faith.pay.provider.core.ProviderPaymentState;
import com.faith.pay.provider.core.ProviderRefundState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaystackPaymentProviderMappingTests {
    @Test
    void mapsKnownPaymentVocabularyWithoutLeakingItIntoDomain() {
        assertThat(PaystackPaymentProvider.paymentState("success")).isEqualTo(ProviderPaymentState.SUCCEEDED);
        assertThat(PaystackPaymentProvider.paymentState("ongoing")).isEqualTo(ProviderPaymentState.PENDING);
        assertThat(PaystackPaymentProvider.paymentState("abandoned")).isEqualTo(ProviderPaymentState.CANCELLED);
    }

    @Test
    void preservesUnknownAsUnknownInsteadOfCallingItFailure() {
        assertThat(PaystackPaymentProvider.paymentState("new_provider_status")).isEqualTo(ProviderPaymentState.UNKNOWN);
        assertThat(PaystackPaymentProvider.refundState("new_refund_status")).isEqualTo(ProviderRefundState.UNKNOWN);
    }

    @Test
    void mapsPaystackProcessedRefundToDomainSuccess() {
        assertThat(PaystackPaymentProvider.refundState("processed")).isEqualTo(ProviderRefundState.SUCCEEDED);
    }
}
