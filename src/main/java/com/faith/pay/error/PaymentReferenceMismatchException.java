package com.faith.pay.error;

import org.springframework.http.HttpStatus;

public class PaymentReferenceMismatchException
        extends AppException {

    public PaymentReferenceMismatchException(
            String expected,
            String received
    ) {

        super(
                ErrorCode.PAYMENT_REFERENCE_MISMATCH,
                HttpStatus.CONFLICT,
                "Expected payment reference "
                        + expected
                        + " but provider returned "
                        + received
        );
    }
}
