CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(120) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    event_key VARCHAR(200) NOT NULL UNIQUE,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'READY',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_expires_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    CONSTRAINT chk_outbox_status
        CHECK (status IN ('READY', 'PROCESSING', 'RETRY', 'PUBLISHED', 'DEAD_LETTER')),
    CONSTRAINT chk_outbox_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_outbox_events_ready
    ON outbox_events(status, next_attempt_at, created_at)
    WHERE status IN ('READY', 'RETRY', 'PROCESSING');
