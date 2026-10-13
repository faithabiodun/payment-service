package com.faith.pay.error;

import org.springframework.http.HttpStatus;

public class IdempotencyKeyReuseException extends AppException {

    public IdempotencyKeyReuseException() {
        super(ErrorCode.IDEMPOTENCY_KEY_REUSED, HttpStatus.CONFLICT,
                "Idempotency key was used for another request");
    }
}
