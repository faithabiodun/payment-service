package com.faith.pay.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class OutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final int MAX_ATTEMPTS = 8;
    private final OutboxRepository repository;
    private final OutboxSink sink;
    private final int batchSize;
    private final int leaseSeconds;

    public OutboxWorker(OutboxRepository repository, OutboxSink sink,
                        @Value("${outbox.batch-size:25}") int batchSize,
                        @Value("${outbox.lease-seconds:120}") int leaseSeconds) {
        this.repository = repository;
        this.sink = sink;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-millis:3000}")
    public void dispatchBatch() {
        for (OutboxEvent event : repository.claimBatch(batchSize, leaseSeconds, MAX_ATTEMPTS)) {
            try {
                sink.dispatch(event);
                repository.markPublished(event.id(), event.attemptCount());
            } catch (RuntimeException failure) {
                long base = Math.min(3600L, 1L << Math.min(event.attemptCount(), 10));
                long delay = base + ThreadLocalRandom.current().nextLong(Math.max(1L, base / 2L));
                repository.markFailed(event.id(), event.attemptCount(), failure.getClass().getSimpleName(),
                        delay, MAX_ATTEMPTS);
                log.warn("Outbox dispatch deferred: id={}, type={}, attempt={}, errorType={}",
                        event.id(), event.eventType(), event.attemptCount(), failure.getClass().getSimpleName());
            }
        }
    }
}
