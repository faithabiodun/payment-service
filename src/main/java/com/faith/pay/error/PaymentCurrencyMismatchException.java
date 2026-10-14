package com.faith.pay.error;

import org.springframework.http.HttpStatus;

public class PaymentCurrencyMismatchException
        extends AppException {

    public PaymentCurrencyMismatchException(
            String expected,
            String received
    ) {

        super(
                ErrorCode.PAYMENT_CURRENCY_MISMATCH,
                HttpStatus.CONFLICT,
                "Expected currency "
                        + expected
                        + " but provider reported "
                        + received
        );
    }
}
