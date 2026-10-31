package com.faith.pay.provider.core;

import com.faith.pay.error.ProviderCommunicationException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.function.Supplier;

/** Applies a provider-wide circuit breaker and concurrency cap; only safe reads are retried. */
@Component
public class ProviderCallExecutor {
    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final BulkheadRegistry bulkheads;
    private final ProviderFailureClassifier classifier;
    private final MeterRegistry meters;

    public ProviderCallExecutor(ProviderFailureClassifier classifier, MeterRegistry meters) {
        this.classifier = classifier;
        this.meters = meters;
        CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordException(classifier::isTransient)
                .build();
        RetryConfig retryConfig = RetryConfig.custom().maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(250L, 2d, 0.5d, 3_000L))
                .retryOnException(failure -> classifier.shouldRetry(ProviderCallOperation.READ_ONLY, failure)).build();
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom().maxConcurrentCalls(20)
                .maxWaitDuration(Duration.ZERO).build();
        this.circuitBreakers = CircuitBreakerRegistry.of(breakerConfig);
        this.retries = RetryRegistry.of(retryConfig);
        this.bulkheads = BulkheadRegistry.of(bulkheadConfig);
    }

    public <T> T execute(PaymentProviderType provider, ProviderCallOperation operation, Supplier<T> call) {
        CircuitBreaker breaker = circuitBreakers.circuitBreaker(provider.name());
        Retry retry = retries.retry(provider.name() + "-read");
        Bulkhead bulkhead = bulkheads.bulkhead(provider.name());
        Supplier<T> guarded = () -> bulkhead.executeSupplier(() -> breaker.executeSupplier(() ->
                operation.retryAllowed()
                        ? retry.executeSupplier(call)
                        : call.get()));
        Timer.Sample sample = Timer.start(meters);
        try {
            T result = guarded.get();
            record(sample, provider, operation, "success");
            return result;
        } catch (CallNotPermittedException failure) {
            record(sample, provider, operation, "rejected");
            throw new ProviderCommunicationException("Provider circuit is open for " + provider, failure);
        } catch (io.github.resilience4j.bulkhead.BulkheadFullException failure) {
            record(sample, provider, operation, "rejected");
            throw new ProviderCommunicationException("Provider concurrency limit reached for " + provider, failure);
        } catch (RuntimeException failure) {
            record(sample, provider, operation, "error");
            throw failure;
        }
    }

    private void record(Timer.Sample sample, PaymentProviderType provider, ProviderCallOperation operation,
                        String outcome) {
        var timer = Timer.builder("payment.provider.request.duration")
                .tag("provider", provider.name().toLowerCase())
                .tag("operation", operation.name().toLowerCase())
                .tag("outcome", outcome)
                .register(meters);
        sample.stop(timer);
        meters.counter("payment.provider.requests", "provider", provider.name().toLowerCase(),
                "operation", operation.name().toLowerCase(), "outcome", outcome).increment();
    }

    public void validateAvailable(PaymentProviderType provider) {
        if (circuitBreakers.circuitBreaker(provider.name()).getState() == CircuitBreaker.State.OPEN) {
            throw new ProviderCommunicationException("Provider circuit is open for " + provider);
        }
    }

}
