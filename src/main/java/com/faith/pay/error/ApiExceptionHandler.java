package com.faith.pay.error;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClientException;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> orderNotFound(
            OrderNotFoundException e
    ) {
        return error(
                HttpStatus.NOT_FOUND,
                "ORDER_NOT_FOUND",
                e.getMessage()
        );
    }

    @ExceptionHandler(PaymentAlreadySuccessfulException.class)
    public ResponseEntity<Map<String, Object>> paymentAlreadySuccessful(
            PaymentAlreadySuccessfulException e
    ) {
        return error(
                HttpStatus.CONFLICT,
                "PAYMENT_ALREADY_SUCCESSFUL",
                e.getMessage()
        );
    }

    @ExceptionHandler(AmountMismatchException.class)
    public ResponseEntity<Map<String, Object>> amountMismatch(
            AmountMismatchException e
    ) {
        return error(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "AMOUNT_MISMATCH",
                e.getMessage()
        );
    }

    @ExceptionHandler(ProviderUnavailableException.class)
    public ResponseEntity<Map<String, Object>> providerUnavailable(
            ProviderUnavailableException e
    ) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PROVIDER_UNAVAILABLE",
                e.getMessage()
        );
    }

    @ExceptionHandler(WebClientException.class)
    public ResponseEntity<Map<String, Object>> providerRequestFailed(
            WebClientException e
    ) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PROVIDER_UNAVAILABLE",
                "Payment provider request failed"
        );
    }

    @ExceptionHandler(RefundLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> refundLimitExceeded(
            RefundLimitExceededException e
    ) {
        return error(
                HttpStatus.CONFLICT,
                "REFUND_LIMIT_EXCEEDED",
                e.getMessage()
        );
    }

    @ExceptionHandler(
            IllegalArgumentException.class
    )
    public ResponseEntity<Map<String, Object>>
    badRequest(
            IllegalArgumentException e
    ) {

        return ResponseEntity
                .badRequest()
                .body(
                        Map.of(
                                "timestamp",
                                Instant.now()
                                        .toString(),

                                "status",
                                400,

                                "error",
                                "BAD_REQUEST",

                                "message",
                                e.getMessage()
                        )
                );
    }


    @ExceptionHandler(
            IllegalStateException.class
    )
    public ResponseEntity<Map<String, Object>>
    conflict(
            IllegalStateException e
    ) {

        return ResponseEntity
                .status(
                        HttpStatus.CONFLICT
                )
                .body(
                        Map.of(
                                "timestamp",
                                Instant.now()
                                        .toString(),

                                "status",
                                409,

                                "error",
                                "CONFLICT",

                                "message",
                                e.getMessage()
                        )
                );
    }

    private ResponseEntity<Map<String, Object>> error(
            HttpStatus status,
            String error,
            String message
    ) {
        return ResponseEntity.status(status)
                .body(Map.of(
                        "timestamp", Instant.now().toString(),
                        "status", status.value(),
                        "error", error,
                        "message", message
                ));
    }
}
