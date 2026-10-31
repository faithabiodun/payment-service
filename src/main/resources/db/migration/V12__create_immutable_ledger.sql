CREATE TABLE ledger_accounts (
    id BIGSERIAL PRIMARY KEY,
    account_code VARCHAR(60) NOT NULL UNIQUE,
    account_name VARCHAR(120) NOT NULL,
    account_type VARCHAR(30) NOT NULL CHECK (account_type IN
        ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'CONTRA_REVENUE', 'EXPENSE')),
    normal_balance VARCHAR(6) NOT NULL CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE journal_entries (
    id BIGSERIAL PRIMARY KEY,
    event_key VARCHAR(200) NOT NULL UNIQUE,
    aggregate_type VARCHAR(30) NOT NULL CHECK (aggregate_type IN ('PAYMENT', 'REFUND')),
    aggregate_reference VARCHAR(120) NOT NULL,
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    description VARCHAR(240) NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_postings (
    id BIGSERIAL PRIMARY KEY,
    journal_entry_id BIGINT NOT NULL REFERENCES journal_entries(id),
    account_id BIGINT NOT NULL REFERENCES ledger_accounts(id),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    debit_minor BIGINT NOT NULL DEFAULT 0 CHECK (debit_minor >= 0),
    credit_minor BIGINT NOT NULL DEFAULT 0 CHECK (credit_minor >= 0),
    CONSTRAINT chk_ledger_one_side CHECK (
        (debit_minor > 0 AND credit_minor = 0) OR (credit_minor > 0 AND debit_minor = 0)
    )
);

CREATE INDEX idx_ledger_postings_account_currency
    ON ledger_postings(account_id, currency, journal_entry_id);

INSERT INTO ledger_accounts(account_code, account_name, account_type, normal_balance) VALUES
    ('PROVIDER_CLEARING_PAYSTACK', 'Paystack Provider Clearing', 'ASSET', 'DEBIT'),
    ('PROVIDER_CLEARING_FLUTTERWAVE', 'Flutterwave Provider Clearing', 'ASSET', 'DEBIT'),
    ('PROVIDER_CLEARING_STRIPE', 'Stripe Provider Clearing', 'ASSET', 'DEBIT'),
    ('SALES_REVENUE', 'Sales Revenue', 'REVENUE', 'CREDIT'),
    ('SALES_RETURNS', 'Sales Returns and Refunds', 'CONTRA_REVENUE', 'DEBIT'),
    ('PAYMENT_PROCESSING_FEES', 'Payment Processing Fee Expense', 'EXPENSE', 'DEBIT');

CREATE FUNCTION enforce_balanced_journal() RETURNS trigger AS $$
DECLARE
    target_id BIGINT;
    posting_count BIGINT;
    currency_count BIGINT;
    journal_currency CHAR(3);
    posting_currency CHAR(3);
    debit_total NUMERIC;
    credit_total NUMERIC;
BEGIN
    IF TG_TABLE_NAME = 'journal_entries' THEN
        target_id := NEW.id;
    ELSE
        target_id := NEW.journal_entry_id;
    END IF;

    SELECT currency INTO journal_currency FROM journal_entries WHERE id = target_id;
    SELECT count(*), count(DISTINCT currency), min(currency),
           COALESCE(sum(debit_minor), 0), COALESCE(sum(credit_minor), 0)
      INTO posting_count, currency_count, posting_currency, debit_total, credit_total
      FROM ledger_postings WHERE journal_entry_id = target_id;

    IF posting_count < 2 OR currency_count <> 1 OR posting_currency <> journal_currency
       OR debit_total <> credit_total THEN
        RAISE EXCEPTION 'Journal % must have at least two postings balanced in one currency', target_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_journal_entries_balanced
    AFTER INSERT ON journal_entries DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_balanced_journal();

CREATE CONSTRAINT TRIGGER trg_ledger_postings_balanced
    AFTER INSERT ON ledger_postings DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_balanced_journal();

CREATE FUNCTION reject_posted_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Posted financial ledger history is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entries_immutable
    BEFORE UPDATE OR DELETE ON journal_entries FOR EACH ROW EXECUTE FUNCTION reject_posted_ledger_mutation();
CREATE TRIGGER trg_ledger_postings_immutable
    BEFORE UPDATE OR DELETE ON ledger_postings FOR EACH ROW EXECUTE FUNCTION reject_posted_ledger_mutation();
