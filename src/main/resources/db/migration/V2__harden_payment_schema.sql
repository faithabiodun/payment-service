-- A provider transaction must belong to at most one local payment.
CREATE UNIQUE INDEX IF NOT EXISTS
    uq_payments_provider_transaction_id
    ON payments(provider_transaction_id)
    WHERE provider_transaction_id IS NOT NULL;


-- Helps reconciliation quickly find unfinished payments.
CREATE INDEX IF NOT EXISTS
    idx_payments_reconciliation
    ON payments(status, updated_at)
    WHERE status IN ('PENDING', 'PROCESSING');


-- Audit events are history.
-- Application code should never modify or delete them.
CREATE OR REPLACE FUNCTION
    prevent_payment_event_mutation()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'payment_events is append-only';
END;
$$;


DROP TRIGGER IF EXISTS
    trg_payment_events_append_only
    ON payment_events;


CREATE TRIGGER
    trg_payment_events_append_only
    BEFORE UPDATE OR DELETE
    ON payment_events
    FOR EACH ROW
EXECUTE FUNCTION prevent_payment_event_mutation();