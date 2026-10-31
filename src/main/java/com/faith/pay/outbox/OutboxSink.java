package com.faith.pay.outbox;

/** A downstream adapter. Implementations must tolerate redelivery by event ID. */
public interface OutboxSink {
    void dispatch(OutboxEvent event);
}
