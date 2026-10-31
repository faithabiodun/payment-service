package com.faith.pay.provider.core;

public record ProviderRefundRequest(String paymentReference, String refundReference,
                                    long amountMinor, String reason, String idempotencyKey) { }
