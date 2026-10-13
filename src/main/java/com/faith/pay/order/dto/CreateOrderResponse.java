package com.faith.pay.order.dto;

import com.faith.pay.order.Order;

public record CreateOrderResponse(

        Long id,

        String customerEmail,

        long amountKobo,

        String status
) {

    public static CreateOrderResponse from(Order order) {
        return new CreateOrderResponse(
                order.getId(),
                order.getCustomerEmail(),
                order.getTotalKobo(),
                order.getStatus().name()
        );
    }
}
