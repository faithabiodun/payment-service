package com.faith.pay.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/test-orders")
public class TestOrderController {
    private final OrderRepository orderRepository;

    public TestOrderController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public record CreateOrderRequest(
            @NotBlank
            @Email
            String email,

            @Positive
            long amountKobo
    ){ }

    @PostMapping
    public  ResponseEntity<Order> create(
            @Valid
            @RequestBody
            CreateOrderRequest request
    ){
        Order order = new Order(
                request.email(),
                request.amountKobo()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(orderRepository.save(order));
    }
}
