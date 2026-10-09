package com.faith.pay.error;

public class PaymentAlreadySuccessfulException extends RuntimeException {

    public PaymentAlreadySuccessfulException(String message) {
        super(message);
    }
}
