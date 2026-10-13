package com.faith.pay.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiError> appException(AppException e) {
        return error(e.getStatus(), e.getCode(), e.getMessage());
    }

    @ExceptionHandler({WebClientException.class, ProviderCommunicationException.class})
    public ResponseEntity<ApiError> providerRequestFailed(Exception e) {
        log.error("Payment provider request failed", e);
        return error(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.PROVIDER_UNAVAILABLE,
                "Payment provider request failed");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpectedException(Exception e) {
        log.error("Unexpected API failure", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        ErrorCode code = status.is4xxClientError() ? ErrorCode.BAD_REQUEST : ErrorCode.INTERNAL_ERROR;
        String message = status.is4xxClientError()
                ? "Request is invalid" : "An unexpected error occurred";
        return new ResponseEntity<>(new ApiError(Instant.now(), status.value(), code, message),
                headers, status);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, ErrorCode code, String message) {
        return ResponseEntity.status(status)
                .body(new ApiError(Instant.now(), status.value(), code, message));
    }
}
