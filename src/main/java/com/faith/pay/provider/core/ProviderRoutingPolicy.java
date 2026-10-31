package com.faith.pay.provider.core;

/** Selects one provider before a payment attempt is persisted. */
public interface ProviderRoutingPolicy {
    PaymentProviderType choose(PaymentProviderType requestedProvider);
}
