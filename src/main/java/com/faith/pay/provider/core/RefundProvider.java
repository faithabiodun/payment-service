package com.faith.pay.provider.core;

import java.util.List;

public interface RefundProvider {
    PaymentProviderType type();
    ProviderRefundResult refund(ProviderRefundRequest request);
    VerifiedRefund verifyRefund(String providerRefundId, String currency);
    List<VerifiedRefund> findRefundsForPayment(String providerPaymentId, String localRefundReference, String currency);
}
