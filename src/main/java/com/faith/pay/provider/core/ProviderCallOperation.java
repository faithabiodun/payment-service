package com.faith.pay.provider.core;

public enum ProviderCallOperation {
    READ_ONLY(true),
    MONEY_MOVING(false);

    private final boolean retryAllowed;
    ProviderCallOperation(boolean retryAllowed) { this.retryAllowed = retryAllowed; }
    public boolean retryAllowed() { return retryAllowed; }
}
