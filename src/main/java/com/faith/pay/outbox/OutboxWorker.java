package com.faith.pay.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
        Instant leaseDeadline = Instant.now().plusSeconds(leaseSeconds > 5 ? leaseSeconds - 5L : Math.max(1L, leaseSeconds));
        for (OutboxEvent event : repository.claimBatch(batchSize, leaseSeconds, MAX_ATTEMPTS)) {
            if (Instant.now().isAfter(leaseDeadline)) {
                log.warn("Stopping outbox batch early because lease deadline expired; remaining events will be reclaimed");
                break;
            }
            try {
                sink.dispatch(event);
            } catch (RuntimeException failure) {
                long base = Math.min(3600L, 1L << Math.min(event.attemptCount(), 10));
                long delay = base + ThreadLocalRandom.current().nextLong(Math.max(1L, base / 2L));
                try {
                    repository.markFailed(event.id(), event.attemptCount(), failure.getClass().getSimpleName(),
                            delay, MAX_ATTEMPTS);
                } catch (RuntimeException dbFailure) {
                    log.error("Failed to record outbox dispatch failure: id={}, type={}, attempt={}",
                            event.id(), event.eventType(), event.attemptCount(), dbFailure);
                }
                log.warn("Outbox dispatch deferred: id={}, type={}, attempt={}, errorType={}",
                        event.id(), event.eventType(), event.attemptCount(), failure.getClass().getSimpleName());
                continue;
            }

            try {
                repository.markPublished(event.id(), event.attemptCount());
            } catch (RuntimeException dbFailure) {
                // Event was already dispatched; do not invoke markFailed or dead-letter it.
                log.error("Outbox event dispatched but failed to mark PUBLISHED: id={}, type={}, attempt={}",
                        event.id(), event.eventType(), event.attemptCount(), dbFailure);
            }
        }
    }
}
