package com.faith.pay.provider.core;

import java.time.Instant;

public record VerifiedPayment(PaymentProviderType provider, String providerPaymentId, String reference,
                              ProviderPaymentState state, long amountMinor, String currency,
                              String channel, Long feeMinor, Instant paidAt) {
    public boolean succeeded() { return state == ProviderPaymentState.SUCCEEDED; }
}
