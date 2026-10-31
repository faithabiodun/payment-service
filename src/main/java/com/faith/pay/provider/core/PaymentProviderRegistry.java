package com.faith.pay.provider.core;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Builds a provider lookup from Spring's injected implementations. */
@Component
public class PaymentProviderRegistry {
    private final Map<PaymentProviderType, PaymentProvider> providers;

    public PaymentProviderRegistry(List<PaymentProvider> providers, ProviderCallExecutor calls) {
        EnumMap<PaymentProviderType, PaymentProvider> byType = new EnumMap<>(PaymentProviderType.class);
        for (PaymentProvider provider : providers) {
            if (byType.put(provider.type(), new ResilientPaymentProvider(provider, calls)) != null) {
                throw new IllegalStateException("Multiple payment providers registered for " + provider.type());
            }
        }
        this.providers = Map.copyOf(byType);
    }

    public PaymentProvider get(PaymentProviderType type) {
        PaymentProvider provider = providers.get(type);
        if (provider == null) throw new IllegalArgumentException("No payment provider registered for " + type);
        return provider;
    }
}
