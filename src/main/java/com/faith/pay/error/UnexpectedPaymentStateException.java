package com.faith.pay.error;

import org.springframework.http.HttpStatus;

public class UnexpectedPaymentStateException
        extends AppException {

    public UnexpectedPaymentStateException(
            String reference,
            String state
    ) {

        super(
                ErrorCode.INVALID_PAYMENT_STATE,
                HttpStatus.CONFLICT,
                "Payment "
                        + reference
                        + " cannot become successful "
                        + "from state "
                        + state
        );
    }
}
