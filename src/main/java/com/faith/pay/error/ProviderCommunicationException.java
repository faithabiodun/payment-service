package com.faith.pay.error;

import org.springframework.http.HttpStatus;

public class ProviderCommunicationException extends AppException {

    public ProviderCommunicationException(String message) {
        super(ErrorCode.PROVIDER_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public ProviderCommunicationException(String message, Throwable cause) {
        super(ErrorCode.PROVIDER_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }
}
