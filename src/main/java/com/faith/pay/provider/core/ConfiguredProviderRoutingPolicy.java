package com.faith.pay.provider.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Uses a customer-selected provider when present, otherwise a stable configured default. */
@Component
public class ConfiguredProviderRoutingPolicy implements ProviderRoutingPolicy {
    private final PaymentProviderType defaultProvider;

    public ConfiguredProviderRoutingPolicy(
            @Value("${payment.routing.default-provider:PAYSTACK}") PaymentProviderType defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    @Override
    public PaymentProviderType choose(PaymentProviderType requestedProvider) {
        return requestedProvider == null ? defaultProvider : requestedProvider;
    }
}
