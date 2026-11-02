package com.faith.pay.provider.core;

import com.faith.pay.error.AppException;
import com.faith.pay.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
        PaymentProviderType selected = requestedProvider == null ? defaultProvider : requestedProvider;
        if (selected == PaymentProviderType.FLUTTERWAVE) {
            throw new AppException(ErrorCode.PROVIDER_NOT_AVAILABLE, HttpStatus.UNPROCESSABLE_ENTITY,
                    "Flutterwave is no longer available for new payment attempts");
        }
        return selected;
    }
}
