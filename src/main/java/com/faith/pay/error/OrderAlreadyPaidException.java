package com.faith.pay.error;

import org.springframework.http.HttpStatus;

public class OrderAlreadyPaidException
        extends AppException {

    public OrderAlreadyPaidException(
            Long id
    ) {

        super(
                ErrorCode.ORDER_ALREADY_PAID,
                HttpStatus.CONFLICT,
                "Order " + id + " is already paid"
        );
    }
}