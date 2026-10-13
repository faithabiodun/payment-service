package com.faith.pay.error;

import org.springframework.http.HttpStatus;

public class PaymentNotFoundException extends AppException {

    public PaymentNotFoundException(String reference) {
        super(ErrorCode.PAYMENT_NOT_FOUND, HttpStatus.NOT_FOUND,
                "Payment " + reference + " was not found");
    }
}
