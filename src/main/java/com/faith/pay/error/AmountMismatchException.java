package com.faith.pay.error;

public class AmountMismatchException extends RuntimeException {

    public AmountMismatchException(String message) {
        super(message);
    }
}
