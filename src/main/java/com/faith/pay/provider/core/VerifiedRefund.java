package com.faith.pay.provider.core;

public record VerifiedRefund(PaymentProviderType provider, String providerRefundId,
                             String refundReference, ProviderRefundState state,
                             long amountMinor, String currency, String providerStatus) { }
