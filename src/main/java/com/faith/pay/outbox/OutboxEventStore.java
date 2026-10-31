package com.faith.pay.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Repository
public class OutboxEventStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutboxEventStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Called inside the financial transition transaction so state and event commit together. */
    public void append(String aggregateType, String aggregateId, String eventType, Object payload) {
        try {
            String json = mapper.writeValueAsString(payload);
            jdbc.update("""
                    INSERT INTO outbox_events(aggregate_type, aggregate_id, event_type, event_key, payload)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb))
                    ON CONFLICT (event_key) DO NOTHING
                    """, aggregateType, aggregateId, eventType, UUID.randomUUID().toString(), json);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not serialize outbox event", failure);
        }
    }
}
