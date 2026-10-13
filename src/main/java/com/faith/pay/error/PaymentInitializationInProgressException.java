package com.faith.pay.error;

import org.springframework.http.HttpStatus;

public class PaymentInitializationInProgressException extends AppException {

    public PaymentInitializationInProgressException(Long orderId) {
        super(ErrorCode.PAYMENT_INITIALIZATION_IN_PROGRESS, HttpStatus.CONFLICT,
                "Payment initialization is already in progress for order " + orderId);
    }
}
