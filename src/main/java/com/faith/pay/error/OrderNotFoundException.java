package com.faith.pay.error;

import org.springframework.http.HttpStatus;

public class OrderNotFoundException
        extends AppException {

    public OrderNotFoundException(
            Long id
    ) {

        super(
                ErrorCode.ORDER_NOT_FOUND,
                HttpStatus.NOT_FOUND,
                "Order " + id + " was not found"
        );
    }
}