package com.faith.pay.provider.core;

public record ProviderRefundRequest(String paymentReference, String providerPaymentId,
                                    String refundReference, long amountMinor, String currency,
                                    String reason, String idempotencyKey) { }
