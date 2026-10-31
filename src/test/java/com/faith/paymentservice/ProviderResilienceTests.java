package com.faith.paymentservice;

import com.faith.pay.error.ProviderCommunicationException;
import com.faith.pay.provider.core.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProviderResilienceTests {
    private WebClientResponseException response(int status) {
        var response = mock(WebClientResponseException.class);
        when(response.getStatusCode()).thenReturn(HttpStatus.valueOf(status));
        return response;
    }

    @Test
    void classifies429And5xxAsTransientBut400AsPermanent() {
        var classifier = new ProviderFailureClassifier();

        assertThat(classifier.shouldRetry(ProviderCallOperation.READ_ONLY,
                new ProviderCommunicationException("rate limited", response(429)))).isTrue();
        assertThat(classifier.shouldRetry(ProviderCallOperation.READ_ONLY,
                new ProviderCommunicationException("provider down", response(503)))).isTrue();
        assertThat(classifier.shouldRetry(ProviderCallOperation.READ_ONLY,
                new ProviderCommunicationException("invalid request", response(400)))).isFalse();
        assertThat(classifier.shouldRetry(ProviderCallOperation.READ_ONLY,
                new ProviderCommunicationException("invalid credentials", response(401)))).isFalse();
        assertThat(classifier.shouldRetry(ProviderCallOperation.READ_ONLY,
                new ProviderCommunicationException("connection reset", new IOException()))).isTrue();
    }

    @Test
    void retriesReadOnlyCallsWithTransientFailuresUntilSuccess() {
        var calls = new ProviderCallExecutor(new ProviderFailureClassifier());
        var attempts = new AtomicInteger();
        String result = calls.execute(PaymentProviderType.STRIPE, ProviderCallOperation.READ_ONLY, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ProviderCommunicationException("provider temporarily unavailable", response(503));
            }
            return "verified";
        });

        assertThat(result).isEqualTo("verified");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void doesNotRetryMoneyMovingPostsEvenWhenProviderReturns5xx() {
        var calls = new ProviderCallExecutor(new ProviderFailureClassifier());
        var attempts = new AtomicInteger();

        assertThatThrownBy(() -> calls.execute(PaymentProviderType.FLUTTERWAVE,
                ProviderCallOperation.MONEY_MOVING, () -> {
                    attempts.incrementAndGet();
                    throw new ProviderCommunicationException("ambiguous create", response(503));
                })).isInstanceOf(ProviderCommunicationException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void circuitOpensAfterRepeatedTransientMoneyCallFailures() {
        var calls = new ProviderCallExecutor(new ProviderFailureClassifier());
        var attempts = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> calls.execute(PaymentProviderType.PAYSTACK,
                    ProviderCallOperation.MONEY_MOVING, () -> {
                        attempts.incrementAndGet();
                        throw new ProviderCommunicationException("provider down", response(503));
                    })).isInstanceOf(ProviderCommunicationException.class);
        }

        assertThatThrownBy(() -> calls.execute(PaymentProviderType.PAYSTACK,
                ProviderCallOperation.MONEY_MOVING, () -> {
                    attempts.incrementAndGet();
                    return "unexpected";
                })).isInstanceOf(ProviderCommunicationException.class).hasMessageContaining("circuit is open");
        assertThat(attempts).hasValue(5);
    }
}
