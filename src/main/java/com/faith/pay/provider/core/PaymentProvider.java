package com.faith.pay.provider.core;

public interface PaymentProvider {
    PaymentProviderType type();
    ProviderPaymentResult initialize(ProviderPaymentRequest request);
    VerifiedPayment verify(String reference, String providerPaymentId);
}
