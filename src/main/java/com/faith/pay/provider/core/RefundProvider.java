package com.faith.pay.provider.core;

public interface RefundProvider {
    PaymentProviderType type();
    ProviderRefundResult refund(ProviderRefundRequest request);
    VerifiedRefund verifyRefund(String providerRefundId);
}
