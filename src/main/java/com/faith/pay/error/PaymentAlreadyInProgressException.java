package com.faith.pay.error;

import org.springframework.http.HttpStatus;

public class PaymentAlreadyInProgressException extends AppException {

    public PaymentAlreadyInProgressException(Long orderId) {
        super(ErrorCode.PAYMENT_ALREADY_IN_PROGRESS, HttpStatus.CONFLICT,
                "There is already a payment in progress for order " + orderId);
    }
}
