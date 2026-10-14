package com.faith.pay.error;

import org.springframework.http.HttpStatus;

public class PaymentAmountMismatchException
        extends AppException {

    public PaymentAmountMismatchException(
            long expected,
            long received
    ) {

        super(
                ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                HttpStatus.CONFLICT,
                "Expected "
                        + expected
                        + " kobo but provider reported "
                        + received
                        + " kobo"
        );
    }
}
