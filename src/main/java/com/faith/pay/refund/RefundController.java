package com.faith.pay.refund;

import com.faith.pay.refund.dto.RefundRequest;
import com.faith.pay.refund.dto.RefundResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/v1/payments/{paymentReference}/refunds")
public class RefundController {

    private final RefundService service;

    public RefundController(RefundService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RefundResponse> refund(
            @PathVariable String paymentReference,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RefundRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.refund(paymentReference, request, idempotencyKey));
    }
}
