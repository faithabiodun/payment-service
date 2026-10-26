ALTER TABLE refunds
ADD COLUMN requested_amount_kobo BIGINT;

UPDATE refunds
SET requested_amount_kobo = amount_kobo
WHERE requested_amount_kobo IS NULL;

ALTER TABLE refunds
ADD CONSTRAINT chk_refunds_requested_amount_positive
CHECK (
    requested_amount_kobo IS NULL
    OR requested_amount_kobo > 0
);
