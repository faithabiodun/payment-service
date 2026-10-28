# Testing samples

These values are synthetic. They are safe for local request-shape testing and are not customer or Paystack account data.

## 1. Create an order

```http
POST http://localhost:8080/api/v1/test-orders
Content-Type: application/json
```

```json
{
  "email": "sam@example.test",
  "amountKobo": 500000
}
```

Save the returned `id` as `orderId`.

## 2. Initialize checkout

```http
POST http://localhost:8080/api/v1/payments/initialize
Idempotency-Key: sample-payment-20261028-001
Content-Type: application/json
```

```json
{
  "orderId": 1
}
```

Replace `1` with the returned order ID. The response supplies the local payment reference and Paystack authorization URL. Complete the sandbox checkout before testing provider verification or refunds.

## 3. Verify payment

```http
POST http://localhost:8080/api/v1/payments/PAY-REPLACE-WITH-RESPONSE-REFERENCE/verify
```

Expected after a completed Paystack test checkout: `providerStatus` is `success`, `settlementApplied` is true the first time, and `payment.status` is `SUCCESS`.

## 4. Request a partial refund

Only use the actual `reference` from a successful Paystack test payment. The provider rejects made-up provider transaction IDs.

```http
POST http://localhost:8080/api/v1/payments/PAY-REPLACE-WITH-RESPONSE-REFERENCE/refunds
Idempotency-Key: sample-refund-20261028-001
Content-Type: application/json
```

```json
{
  "amountKobo": 125000,
  "reason": "sample partial refund"
}
```

This requests NGN 1,250 from the NGN 5,000 sample payment. For a full refund of the remaining amount, use:

```json
{
  "amountKobo": null,
  "reason": "sample full refund"
}
```

Use a new idempotency key for a new refund request. Reusing a key with different amount or reason is rejected.

## 5. Local signed webhook simulation

The Postman collection's **Simulate Paystack Success Webhook** request signs the request body with HMAC-SHA512. Set its `paystackSecretKey` environment variable to the same local test secret used by `PAYSTACK_SECRET_KEY`. The synthetic webhook is for exercising local webhook handling only; it does not create a transaction at Paystack.

## 6. Reconciliation inspection

To make a real in-flight payment eligible for the next reconciliation run in a disposable local database:

```sql
UPDATE payments
SET updated_at = now() - interval '11 minutes'
WHERE reference = 'YOUR_REAL_PAYMENT_REFERENCE'
  AND status IN ('PENDING', 'PROCESSING');
```

For an in-flight refund:

```sql
UPDATE refunds
SET updated_at = now() - interval '11 minutes'
WHERE reference = 'YOUR_LOCAL_REFUND_REFERENCE'
  AND status IN ('PENDING', 'PROCESSING', 'NEEDS_ATTENTION');
```

Check the operational attempts:

```sql
SELECT target_type, target_reference, outcome, provider_status, details, created_at
FROM reconciliation_attempts
ORDER BY id DESC
LIMIT 50;
```

Check manual-review cases:

```sql
SELECT reference, status, reconciliation_attempts, last_reconciled_at, updated_at
FROM payments
WHERE manual_review_required = TRUE;

SELECT reference, status, provider_refund_id, reconciliation_attempts, last_reconciled_at, updated_at
FROM refunds
WHERE manual_review_required = TRUE;
```
