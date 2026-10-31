package com.faith.pay.provider.core;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class RefundProviderRegistry {
    private final Map<PaymentProviderType, RefundProvider> providers;

    public RefundProviderRegistry(List<RefundProvider> providers) {
        EnumMap<PaymentProviderType, RefundProvider> byType = new EnumMap<>(PaymentProviderType.class);
        for (RefundProvider provider : providers) {
            if (byType.put(provider.type(), provider) != null) {
                throw new IllegalStateException("Multiple refund providers registered for " + provider.type());
            }
        }
        this.providers = Map.copyOf(byType);
    }

    public RefundProvider get(PaymentProviderType type) {
        RefundProvider provider = providers.get(type);
        if (provider == null) throw new IllegalArgumentException("No refund provider registered for " + type);
        return provider;
    }
}
