package com.faith.pay.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<OutboxEvent> MAPPER = (rs, row) -> new OutboxEvent(rs.getLong("id"),
            rs.getString("aggregate_type"), rs.getString("aggregate_id"), rs.getString("event_type"),
            rs.getString("payload"), rs.getInt("attempt_count"));

    public OutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<OutboxEvent> claimBatch(int batchSize, int leaseSeconds, int maxAttempts) {
        jdbc.update("""
                UPDATE outbox_events SET status='DEAD_LETTER',
                    last_error='Dispatch lease expired after retry limit', lease_expires_at=NULL
                WHERE status='PROCESSING' AND lease_expires_at <= now() AND attempt_count >= ?
                """, maxAttempts);
        return jdbc.query("""
                WITH picked AS (
                    SELECT id FROM outbox_events
                    WHERE ((status IN ('READY', 'RETRY') AND next_attempt_at <= now())
                        OR (status='PROCESSING' AND lease_expires_at <= now() AND attempt_count < ?))
                    ORDER BY created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE outbox_events AS event
                SET status='PROCESSING', attempt_count=event.attempt_count + 1,
                    lease_expires_at=now() + (? * interval '1 second')
                FROM picked WHERE event.id=picked.id
                RETURNING event.id, event.aggregate_type, event.aggregate_id, event.event_type,
                    event.payload, event.attempt_count
                """, MAPPER, maxAttempts, batchSize, leaseSeconds);
    }

    public void markPublished(long id, int attemptCount) {
        jdbc.update("""
                UPDATE outbox_events SET status='PUBLISHED', published_at=now(),
                    lease_expires_at=NULL, last_error=NULL
                WHERE id=? AND status='PROCESSING' AND attempt_count=?
                """, id, attemptCount);
    }

    public void markFailed(long id, int attemptCount, String safeError, long delaySeconds, int maxAttempts) {
        jdbc.update("""
                UPDATE outbox_events SET status=?, next_attempt_at=now() + (? * interval '1 second'),
                    last_error=?, lease_expires_at=NULL
                WHERE id=? AND status='PROCESSING' AND attempt_count=?
                """, attemptCount >= maxAttempts ? "DEAD_LETTER" : "RETRY",
                delaySeconds, safeError, id, attemptCount);
    }
}
