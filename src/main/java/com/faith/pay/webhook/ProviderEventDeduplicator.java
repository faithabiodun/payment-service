package com.faith.pay.webhook;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProviderEventDeduplicator {

    private final JdbcTemplate jdbc;

    public ProviderEventDeduplicator(
            JdbcTemplate jdbc
    ) {
        this.jdbc = jdbc;
    }


    public boolean claim(
            String eventType,
            String providerObjectId
    ) {

        //noinspection SqlResolve
        int inserted =
                jdbc.update("""
                        INSERT INTO processed_provider_events(
                            event_type,
                            provider_object_id
                        )
                        VALUES (?, ?)
                        ON CONFLICT DO NOTHING
                        """,

                        eventType,
                        providerObjectId
                );


        return inserted == 1;
    }
}