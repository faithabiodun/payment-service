package com.faith.pay.provider.core;

import com.stripe.exception.StripeException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/** Classifies transport/provider errors; HTTP 4xx is not treated as transient by default. */
@Component
public class ProviderFailureClassifier {
    public boolean isTransient(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof WebClientResponseException response) {
                int status = response.getStatusCode().value();
                return status == 429 || status >= 500;
            }
            if (current instanceof StripeException response && response.getStatusCode() != null) {
                int status = response.getStatusCode();
                return status == 429 || status >= 500;
            }
            if (current instanceof WebClientRequestException || current instanceof IOException
                    || current instanceof SocketTimeoutException || current instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    public boolean shouldRetry(ProviderCallOperation operation, Throwable failure) {
        return operation.retryAllowed() && isTransient(failure);
    }
}
