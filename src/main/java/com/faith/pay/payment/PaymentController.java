package com.faith.pay.payment;

import com.faith.pay.payment.dto.*;

import jakarta.validation.Valid;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(
            PaymentService service
    ) {
        this.service = service;
    }


    @PostMapping("/initialize")
    public ResponseEntity<
            InitializePaymentResponse
            > initialize(

            @RequestHeader("Idempotency-Key")
            String idempotencyKey,

            @Valid
            @RequestBody
            InitializePaymentRequest request
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        service.initialize(
                                request,
                                idempotencyKey
                        )
                );
    }


    @GetMapping("/{reference}")
    public PaymentResponse get(
            @PathVariable
            String reference
    ) {

        return service.get(reference);
    }
}