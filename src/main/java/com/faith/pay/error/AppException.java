package com.faith.pay.error;

import org.springframework.http.HttpStatus;

public class AppException
        extends RuntimeException {

    private final ErrorCode code;

    private final HttpStatus status;


    public AppException(
            ErrorCode code,
            HttpStatus status,
            String message
    ) {
        super(message);

        this.code = code;
        this.status = status;
    }

    public AppException(
            ErrorCode code,
            HttpStatus status,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }


    public ErrorCode getCode() {
        return code;
    }


    public HttpStatus getStatus() {
        return status;
    }
}
