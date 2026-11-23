package com.faith.pay.order;

import com.faith.pay.order.dto.CreateOrderRequest;
import com.faith.pay.order.dto.CreateOrderResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/test-orders")
@ConditionalOnProperty(name = "app.test-orders.enabled", havingValue = "true")
@PreAuthorize("hasRole('ADMIN')")
public class TestOrderController {
    private final OrderRepository orderRepository;

    public TestOrderController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @PostMapping
    public ResponseEntity<CreateOrderResponse> create(
            @Valid
            @RequestBody
            CreateOrderRequest request
    ) {
        Order order = new Order(
                request.email(),
                request.amountKobo()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(CreateOrderResponse.from(orderRepository.save(order)));
    }
}
