CREATE TABLE webhook_inbox (
    id BIGSERIAL PRIMARY KEY,
    provider VARCHAR(20) NOT NULL,
    provider_event_id VARCHAR(255),
    deduplication_key VARCHAR(64) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    raw_payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error VARCHAR(500),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processing_started_at TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ,
    CONSTRAINT chk_webhook_inbox_provider
        CHECK (provider IN ('PAYSTACK', 'FLUTTERWAVE', 'STRIPE')),
    CONSTRAINT chk_webhook_inbox_status
        CHECK (status IN ('RECEIVED', 'PROCESSING', 'RETRY', 'PROCESSED', 'DEAD_LETTER')),
    CONSTRAINT chk_webhook_inbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT uq_webhook_inbox_dedup UNIQUE (provider, deduplication_key)
);

CREATE INDEX idx_webhook_inbox_ready
    ON webhook_inbox(status, next_attempt_at, received_at)
    WHERE status IN ('RECEIVED', 'RETRY', 'PROCESSING');
