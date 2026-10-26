package com.faith.pay.error;

import org.springframework.http.HttpStatus;

public class RefundAmountExceedsRemainingException extends AppException {

    public RefundAmountExceedsRemainingException(long requested, long remaining) {
        super(
                ErrorCode.REFUND_AMOUNT_EXCEEDS_REMAINING,
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Requested refund of " + requested
                        + " kobo exceeds remaining " + remaining + " kobo"
        );
    }
}
