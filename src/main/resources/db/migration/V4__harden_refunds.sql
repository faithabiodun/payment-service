ALTER TABLE refunds
DROP CONSTRAINT IF EXISTS refunds_status_check;

ALTER TABLE refunds
ADD CONSTRAINT chk_refunds_status
CHECK (
    status IN (
        'PENDING',
        'PROCESSING',
        'NEEDS_ATTENTION',
        'SUCCESS',
        'FAILED'
    )
);

ALTER TABLE refunds
ADD COLUMN IF NOT EXISTS provider_status VARCHAR(30);

ALTER TABLE refunds
ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ
NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_refunds_payment_status
ON refunds(payment_id, status);

CREATE INDEX IF NOT EXISTS idx_refunds_provider_id
ON refunds(provider_refund_id)
WHERE provider_refund_id IS NOT NULL;
