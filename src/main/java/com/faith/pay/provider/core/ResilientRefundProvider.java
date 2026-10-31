package com.faith.pay.provider.core;

import java.util.List;

final class ResilientRefundProvider implements RefundProvider {
    private final RefundProvider delegate;
    private final ProviderCallExecutor calls;

    ResilientRefundProvider(RefundProvider delegate, ProviderCallExecutor calls) {
        this.delegate = delegate;
        this.calls = calls;
    }

    @Override public PaymentProviderType type() { return delegate.type(); }
    @Override public void validateConfiguration() {
        delegate.validateConfiguration();
        calls.validateAvailable(type());
    }

    @Override
    public ProviderRefundResult refund(ProviderRefundRequest request) {
        return calls.execute(type(), ProviderCallOperation.MONEY_MOVING, () -> delegate.refund(request));
    }

    @Override
    public VerifiedRefund verifyRefund(String providerRefundId, String currency) {
        return calls.execute(type(), ProviderCallOperation.READ_ONLY,
                () -> delegate.verifyRefund(providerRefundId, currency));
    }

    @Override
    public List<VerifiedRefund> findRefundsForPayment(String providerPaymentId, String localRefundReference,
                                                      String currency) {
        return calls.execute(type(), ProviderCallOperation.READ_ONLY,
                () -> delegate.findRefundsForPayment(providerPaymentId, localRefundReference, currency));
    }
}
