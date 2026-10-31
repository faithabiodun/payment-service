package com.faith.pay.provider.core;

public record ProviderPaymentResult(PaymentProviderType provider, String providerPaymentId, String reference,
                                   ProviderPaymentState state, String checkoutUrl, String clientSecret,
                                   String providerSessionId) { }
