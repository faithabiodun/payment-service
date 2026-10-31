package com.faith.pay.provider.core;

public record ProviderPaymentRequest(String reference, String customerEmail, long amountMinor,
                                     String currency, String callbackUrl, String idempotencyKey) {
    public ProviderPaymentRequest {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("reference is required");
        if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
        if (currency == null || currency.isBlank()) throw new IllegalArgumentException("currency is required");
    }
}
