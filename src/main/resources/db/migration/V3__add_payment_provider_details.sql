ALTER TABLE payments
ADD COLUMN IF NOT EXISTS channel VARCHAR(30);

ALTER TABLE payments
ADD COLUMN IF NOT EXISTS fees_kobo BIGINT;

ALTER TABLE payments
ADD CONSTRAINT chk_payments_fees_non_negative
CHECK(
    fees_kobo is NULL
    OR fees_kobo>=0
);