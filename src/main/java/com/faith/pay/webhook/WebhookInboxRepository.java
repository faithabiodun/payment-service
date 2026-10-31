package com.faith.pay.webhook;

import com.faith.pay.provider.core.PaymentProviderType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class WebhookInboxRepository {
    private final JdbcTemplate jdbc;

    private static final RowMapper<WebhookInboxEvent> ROW_MAPPER = (rs, row) -> new WebhookInboxEvent(
            rs.getLong("id"), PaymentProviderType.valueOf(rs.getString("provider")),
            rs.getString("provider_event_id"), rs.getString("event_type"),
            rs.getString("raw_payload"), rs.getInt("attempt_count"));

    public WebhookInboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean insert(PaymentProviderType provider, String providerEventId, String deduplicationKey,
                          String eventType, String rawPayload) {
        return jdbc.update("""
                INSERT INTO webhook_inbox(provider, provider_event_id, deduplication_key, event_type, raw_payload)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (provider, deduplication_key) DO NOTHING
                """, provider.name(), providerEventId, deduplicationKey, eventType, rawPayload) == 1;
    }

    public List<WebhookInboxEvent> claimBatch(int batchSize, int leaseSeconds, int maxAttempts) {
        jdbc.update("""
                UPDATE webhook_inbox SET status='DEAD_LETTER',
                    last_error='Processing lease expired after retry limit', lease_expires_at=NULL
                WHERE status='PROCESSING' AND lease_expires_at <= now() AND attempt_count >= ?
                """, maxAttempts);
        return jdbc.query("""
                WITH picked AS (
                    SELECT id FROM webhook_inbox
                    WHERE ((status IN ('RECEIVED', 'RETRY') AND next_attempt_at <= now())
                        OR (status = 'PROCESSING' AND lease_expires_at <= now() AND attempt_count < ?))
                    ORDER BY received_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE webhook_inbox AS inbox
                SET status = 'PROCESSING', attempt_count = inbox.attempt_count + 1,
                    processing_started_at = now(),
                    lease_expires_at = now() + (? * interval '1 second')
                FROM picked
                WHERE inbox.id = picked.id
                RETURNING inbox.id, inbox.provider, inbox.provider_event_id, inbox.event_type,
                    inbox.raw_payload, inbox.attempt_count
                """, ROW_MAPPER, maxAttempts, batchSize, leaseSeconds);
    }

    public void markProcessed(long id, int attempts) {
        jdbc.update("""
                UPDATE webhook_inbox SET status='PROCESSED', processed_at=now(),
                    lease_expires_at=NULL, last_error=NULL WHERE id=? AND status='PROCESSING' AND attempt_count=?
                """, id, attempts);
    }

    public void markFailed(long id, int attempts, String safeError, long delaySeconds, int maxAttempts) {
        boolean dead = attempts >= maxAttempts;
        jdbc.update("""
                UPDATE webhook_inbox SET status=?, next_attempt_at=now() + (? * interval '1 second'),
                    last_error=?, lease_expires_at=NULL WHERE id=? AND status='PROCESSING' AND attempt_count=?
                """,
                dead ? "DEAD_LETTER" : "RETRY", delaySeconds, safeError, id, attempts);
    }
}
