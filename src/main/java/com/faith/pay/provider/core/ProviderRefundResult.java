package com.faith.pay.provider.core;

public record ProviderRefundResult(PaymentProviderType provider, String providerRefundId,
                                   String refundReference, ProviderRefundState state,
                                   long amountMinor, String currency, String providerStatus) { }
