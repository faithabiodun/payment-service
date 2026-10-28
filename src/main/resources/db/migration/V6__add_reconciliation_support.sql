ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_status_check;
ALTER TABLE payments ADD CONSTRAINT chk_payments_status CHECK (status IN (
    'PENDING', 'PROCESSING', 'SUCCESS', 'FAILED', 'ABANDONED',
    'PARTIALLY_REFUNDED', 'REFUNDED'
));

ALTER TABLE payments ADD COLUMN reconciliation_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payments ADD COLUMN last_reconciled_at TIMESTAMPTZ;
ALTER TABLE payments ADD COLUMN manual_review_required BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE refunds ADD COLUMN reconciliation_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE refunds ADD COLUMN last_reconciled_at TIMESTAMPTZ;
ALTER TABLE refunds ADD COLUMN manual_review_required BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_payments_reconciliation_candidates
    ON payments(updated_at, status) WHERE status IN ('PENDING', 'PROCESSING') AND manual_review_required = FALSE;
CREATE INDEX idx_refunds_reconciliation_candidates
    ON refunds(updated_at, status) WHERE status IN ('PENDING', 'PROCESSING', 'NEEDS_ATTENTION') AND manual_review_required = FALSE;

CREATE TABLE reconciliation_attempts (
    id BIGSERIAL PRIMARY KEY,
    target_type VARCHAR(20) NOT NULL CHECK (target_type IN ('PAYMENT', 'REFUND')),
    target_reference VARCHAR(100) NOT NULL,
    outcome VARCHAR(40) NOT NULL,
    provider_status VARCHAR(40),
    details VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reconciliation_attempts_target
    ON reconciliation_attempts(target_type, target_reference, created_at);