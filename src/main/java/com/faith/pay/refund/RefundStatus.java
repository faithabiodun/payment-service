package com.faith.pay.refund;

public enum RefundStatus {
    PENDING,
    PROCESSING,
    NEEDS_ATTENTION,
    SUCCESS,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }

    public boolean reservesMoney() {
        return this != FAILED;
    }
}
