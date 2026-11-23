-- Index journal_entry_id on ledger_postings so deferred balance constraint triggers
-- do not perform full sequential scans of the append-only ledger on every commit.
CREATE INDEX IF NOT EXISTS idx_ledger_postings_journal_entry_id
    ON ledger_postings(journal_entry_id);

-- Drop V2's global unique index on provider_transaction_id so V8's provider-scoped
-- unique index (provider, provider_transaction_id) is the authoritative rule.
DROP INDEX IF EXISTS uq_payments_provider_transaction_id;

-- Backfill nullable fees_kobo rows from V3 and enforce non-null default 0.
UPDATE payments SET fees_kobo = 0 WHERE fees_kobo IS NULL;
ALTER TABLE payments ALTER COLUMN fees_kobo SET DEFAULT 0;
ALTER TABLE payments ALTER COLUMN fees_kobo SET NOT NULL;

-- Partial indexes so dead-letter queue depth gauges avoid full scans on historical rows.
CREATE INDEX IF NOT EXISTS idx_webhook_inbox_dead_letter
    ON webhook_inbox(status)
    WHERE status = 'DEAD_LETTER';

CREATE INDEX IF NOT EXISTS idx_outbox_events_dead_letter
    ON outbox_events(status)
    WHERE status = 'DEAD_LETTER';
