package com.faith.pay.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentAudit {

    private final JdbcTemplate jdbc;

    public PaymentAudit(
            JdbcTemplate jdbc
    ) {
        this.jdbc = jdbc;
    }

    public void record(
            Payment payment,
            String eventType,
            PaymentStatus from,
            PaymentStatus to,
            String source,
            String rawPayload
    ) {

        //noinspection SqlResolve
        jdbc.update("""
                INSERT INTO payment_events(
                    payment_id,
                    event_type,
                    from_status,
                    to_status,
                    source,
                    raw_payload
                )
                VALUES (
                    ?, ?, ?, ?, ?,
                    CAST(? AS jsonb)
                )
                """,

                payment.getId(),

                eventType,

                from == null
                        ? null
                        : from.name(),

                to == null
                        ? null
                        : to.name(),

                source,

                rawPayload == null
                        ? "{}"
                        : rawPayload
        );
    }
}
