package com.faith.paymentservice;

import com.faith.pay.config.PaystackProperties;
import com.faith.pay.error.AppException;
import com.faith.pay.error.ErrorCode;
import com.faith.pay.error.ProviderCommunicationException;
import com.faith.pay.provider.PaystackClient;
import com.faith.pay.provider.core.*;
import com.faith.pay.provider.paystack.PaystackPaymentProvider;
import com.stripe.exception.ApiConnectionException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
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
        var calls = new ProviderCallExecutor(new ProviderFailureClassifier(), new SimpleMeterRegistry());
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
        var calls = new ProviderCallExecutor(new ProviderFailureClassifier(), new SimpleMeterRegistry());
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
        var calls = new ProviderCallExecutor(new ProviderFailureClassifier(), new SimpleMeterRegistry());
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

    @Test
    void openCircuitAutomaticallyTransitionsToHalfOpenForValidateAvailable() throws Exception {
        var calls = new ProviderCallExecutor(new ProviderFailureClassifier(), new SimpleMeterRegistry(),
                Duration.ofMillis(80));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> calls.execute(PaymentProviderType.PAYSTACK,
                    ProviderCallOperation.MONEY_MOVING, () -> {
                        throw new ProviderCommunicationException("provider down", response(503));
                    })).isInstanceOf(ProviderCommunicationException.class);
        }

        assertThatThrownBy(() -> calls.validateAvailable(PaymentProviderType.PAYSTACK))
                .isInstanceOf(ProviderCommunicationException.class)
                .hasMessageContaining("circuit is open");

        Thread.sleep(150);

        assertThatCode(() -> calls.validateAvailable(PaymentProviderType.PAYSTACK)).doesNotThrowAnyException();
        assertThat(calls.execute(PaymentProviderType.PAYSTACK, ProviderCallOperation.MONEY_MOVING, () -> "ok"))
                .isEqualTo("ok");
    }

    @Test
    void stripeApiConnectionExceptionWithZeroStatusCodeIsTransient() {
        var classifier = new ProviderFailureClassifier();
        var connectionFailure = new ApiConnectionException("timeout", new SocketTimeoutException("read timed out"));

        assertThat(connectionFailure.getStatusCode()).isEqualTo(0);
        assertThat(classifier.isTransient(connectionFailure)).isTrue();
        assertThat(classifier.shouldRetry(ProviderCallOperation.READ_ONLY,
                new ProviderCommunicationException("stripe failed", connectionFailure))).isTrue();
    }

    @Test
    void providerRegistryRetainsFlutterwaveAndPaystackValidatesConfiguration() {
        var flw = mock(PaymentProvider.class);
        when(flw.type()).thenReturn(PaymentProviderType.FLUTTERWAVE);
        var registry = new PaymentProviderRegistry(List.of(flw),
                new ProviderCallExecutor(new ProviderFailureClassifier(), new SimpleMeterRegistry()));
        assertThat(registry.get(PaymentProviderType.FLUTTERWAVE).type()).isEqualTo(PaymentProviderType.FLUTTERWAVE);

        var emptyProps = new PaystackProperties("https://api.paystack.co", "", "http://localhost/cb");
        var paystack = new PaystackPaymentProvider(new PaystackClient(WebClient.builder().build(), emptyProps));
        assertThatThrownBy(paystack::validateConfiguration)
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getCode()).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE));
    }
}
