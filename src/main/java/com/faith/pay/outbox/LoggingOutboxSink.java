package com.faith.pay.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Local demonstration sink; a real email/queue integration can replace this adapter. */
@Component
public class LoggingOutboxSink implements OutboxSink {
    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxSink.class);

    @Override
    public void dispatch(OutboxEvent event) {
        log.info("Outbox event dispatched: id={}, type={}, aggregateType={}, aggregateId={}",
                event.id(), event.eventType(), event.aggregateType(), event.aggregateId());
    }
}
