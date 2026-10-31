ALTER TABLE reconciliation_attempts
    ADD COLUMN discrepancy_type VARCHAR(40);

ALTER TABLE reconciliation_attempts
    ADD CONSTRAINT chk_reconciliation_discrepancy_type CHECK (
        discrepancy_type IS NULL OR discrepancy_type IN (
            'AMOUNT_MISMATCH', 'CURRENCY_MISMATCH', 'REFERENCE_MISMATCH',
            'PROVIDER_MISMATCH', 'PROVIDER_DATA_MISMATCH', 'UNKNOWN_PROVIDER_STATE',
            'LOCAL_PROVIDER_CONTRADICTION', 'DUPLICATE_PROVIDER_TRANSACTION',
            'MANUAL_REVIEW_REQUIRED'
        )
    );
