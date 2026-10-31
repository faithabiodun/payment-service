package com.faith.pay.provider.core;

/** Decorates a provider port so every registry user receives the same circuit/bulkhead policy. */
final class ResilientPaymentProvider implements PaymentProvider {
    private final PaymentProvider delegate;
    private final ProviderCallExecutor calls;

    ResilientPaymentProvider(PaymentProvider delegate, ProviderCallExecutor calls) {
        this.delegate = delegate;
        this.calls = calls;
    }

    @Override public PaymentProviderType type() { return delegate.type(); }
    @Override public void validateConfiguration() {
        delegate.validateConfiguration();
        calls.validateAvailable(type());
    }

    @Override
    public ProviderPaymentResult initialize(ProviderPaymentRequest request) {
        return calls.execute(type(), ProviderCallOperation.MONEY_MOVING, () -> delegate.initialize(request));
    }

    @Override
    public VerifiedPayment verify(String reference, String providerPaymentId) {
        return calls.execute(type(), ProviderCallOperation.READ_ONLY,
                () -> delegate.verify(reference, providerPaymentId));
    }
}
