package com.faith.pay.error;

import org.springframework.http.HttpStatus;

public class RefundNotAllowedException extends AppException {

    public RefundNotAllowedException(String message) {
        super(ErrorCode.REFUND_NOT_ALLOWED, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
