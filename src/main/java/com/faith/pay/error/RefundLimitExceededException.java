package com.faith.pay.error;

public class RefundLimitExceededException extends RuntimeException {

    public RefundLimitExceededException(String message) {
        super(message);
    }
}
