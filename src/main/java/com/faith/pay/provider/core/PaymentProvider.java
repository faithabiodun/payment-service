package com.faith.pay.provider.core;

public interface PaymentProvider extends ProviderReadiness {
    PaymentProviderType type();
    ProviderPaymentResult initialize(ProviderPaymentRequest request);
    VerifiedPayment verify(String reference, String providerPaymentId);
}
