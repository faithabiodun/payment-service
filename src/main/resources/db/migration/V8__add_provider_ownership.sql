-- All historical provider identifiers belong to Paystack. The default also
-- assigns PAYSTACK to new rows until routing selects another adapter.
ALTER TABLE payments
    ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'PAYSTACK';

ALTER TABLE payments
    ADD CONSTRAINT chk_payments_provider
        CHECK (provider IN ('PAYSTACK', 'FLUTTERWAVE', 'STRIPE'));

-- Provider IDs are only unique within that provider's namespace.
CREATE UNIQUE INDEX uq_payments_provider_transaction
    ON payments(provider, provider_transaction_id)
    WHERE provider_transaction_id IS NOT NULL;

ALTER TABLE refunds
    ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'PAYSTACK';

ALTER TABLE refunds
    ADD CONSTRAINT chk_refunds_provider
        CHECK (provider IN ('PAYSTACK', 'FLUTTERWAVE', 'STRIPE'));

-- Replace the old global uniqueness rule with provider-scoped uniqueness.
ALTER TABLE refunds
    DROP CONSTRAINT IF EXISTS refunds_provider_refund_id_key;

DROP INDEX IF EXISTS idx_refunds_provider_id;

CREATE UNIQUE INDEX uq_refunds_provider_refund_id
    ON refunds(provider, provider_refund_id)
    WHERE provider_refund_id IS NOT NULL;
