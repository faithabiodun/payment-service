package com.faith.pay.payment.audit;

import com.faith.pay.payment.Payment;
import com.faith.pay.payment.PaymentStatus;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentEventStore {

    private final JdbcTemplate jdbc;

    public PaymentEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(
            Payment payment,
            PaymentEventType eventType,
            PaymentStatus from,
            PaymentStatus to,
            PaymentEventSource source,
            String rawPayload
    ) {
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
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    CAST(? AS jsonb)
                )
                """,
                payment.getId(),
                eventType.name(),
                from == null ? null : from.name(),
                to == null ? null : to.name(),
                source.name(),
                rawPayload == null ? "{}" : rawPayload
        );
    }
}
