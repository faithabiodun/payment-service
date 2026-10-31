package com.faith.pay.provider.core;

/** Payment lifecycle vocabulary owned by this application, not by a gateway. */
public enum ProviderPaymentState {
    CREATED,
    REQUIRES_ACTION,
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN
}
