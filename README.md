# Payment Service

A Spring Boot application for initializing and verifying Paystack payments, processing signed webhooks, and recording payment events in PostgreSQL. It also includes a refund service and a scheduled job that verifies stale payments.

Amounts are expressed in **kobo**: `500000` represents NGN 5,000.

## Stack

- Java 17 or newer
- Spring Boot 4.1.1, Spring MVC, and WebClient
- Spring Data JPA and PostgreSQL
- Flyway database migrations
- Maven
- Testcontainers for database tests

## Run locally

Create an empty PostgreSQL database named `payment_service`. Flyway creates the tables when the application starts; Hibernate validates the resulting schema.

Set the following environment variables in your terminal or IntelliJ run configuration:

| Variable | Required | Default / purpose |
| --- | --- | --- |
| `DB_URL` | No | `jdbc:postgresql://localhost:5432/payment_service` |
| `DB_USERNAME` | No | `postgres` |
| `DB_PASSWORD` | Yes | Your PostgreSQL password |
| `PAYSTACK_SECRET_KEY` | Yes | Your Paystack secret key; use a test key for development |
| `PAYSTACK_CALLBACK_URL` | No | `http://localhost:8080/api/v1/payments/callback` |

PowerShell example:

```powershell
$env:DB_PASSWORD = "your-local-database-password"
$env:PAYSTACK_SECRET_KEY = "your-paystack-test-secret-key"
mvn spring-boot:run
```

On macOS or Linux:

```bash
export DB_PASSWORD="your-local-database-password"
export PAYSTACK_SECRET_KEY="your-paystack-test-secret-key"
./mvnw spring-boot:run
```

The application starts on `http://localhost:8080`. Health is available at `/actuator/health`.

A `.env` file is ignored by Git, but Spring Boot does not automatically load it with the current configuration. Supply the variables through the process environment or your IDE. Keep credentials out of source control.

## API

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/v1/test-orders` | Create a development order |
| POST | `/api/v1/payments/initialize` | Initialize a Paystack checkout |
| POST | `/api/v1/payments/{reference}/verify` | Verify a payment with Paystack |
| GET | `/api/v1/payments/{reference}` | Read the stored payment status |
| GET | `/api/v1/payments/callback?reference=...` | Receive the checkout redirect |
| POST | `/webhooks/paystack` | Receive a signed Paystack webhook |

Create a test order:

```json
{
  "email": "faith@example.com",
  "amountKobo": 500000
}
```

Initialize its payment using the returned order ID:

```http
POST /api/v1/payments/initialize
Content-Type: application/json
Idempotency-Key: a-unique-key-for-this-request
```

```json
{
  "orderId": 1
}
```

The amount comes from the stored order. The response includes the payment `reference`, `authorizationUrl`, and `accessCode`. Open the authorization URL to complete checkout, then verify the reference or allow a signed webhook to confirm payment.

The callback redirect alone does not mark a payment successful. Completion validates the provider amount and currency, marks the order paid, and writes a payment event. Repeated success notifications do not apply completion twice.

## Postman

Import [test-orders.postman_collection.json](test-orders.postman_collection.json) and set the collection variable `baseUrl` if your server runs elsewhere.

For a Paystack test checkout:

1. Run **Create Test Order**. Its response saves `orderId`.
2. Run **Initialize Payment**. Its response saves `reference` and `amountKobo`.
3. Complete checkout at the returned `authorizationUrl`.
4. Run **Verify Payment**, then **Get Payment Status**.

Use **Simulate Paystack Success Webhook** to exercise webhook handling locally. Set the Postman environment variable `paystackSecretKey` to the application's test secret and select that environment. The script signs the generated body using HMAC-SHA512.

Simulation creates a synthetic success event; it does not complete a transaction at Paystack. Use an actual test checkout when testing provider verification or refunds. For provider-delivered webhooks, configure a publicly reachable webhook URL ending in `/webhooks/paystack` in your Paystack dashboard.

## Reconciliation

`ReconciliationJob` waits five minutes after each run finishes before starting again. It selects payments in `PROCESSING` whose `updated_at` is more than ten minutes old and calls the payment verification service for each one.

To test recovery from a missed webhook:

1. Initialize and complete a Paystack test checkout.
2. Leave the local payment in `PROCESSING` by preventing webhook delivery and avoiding manual verification.
3. Wait until it is stale, then allow the next scheduled run to verify it.
4. Check for payment status `SUCCESS` and a `PAYMENT_SUCCEEDED` event with source `VERIFICATION`.

To shorten the wait for one payment in a development database:

```sql
UPDATE payments
SET updated_at = now() - interval '11 minutes'
WHERE reference = 'YOUR_PAYMENT_REFERENCE'
  AND status = 'PROCESSING';
```

The next scheduled run can still take up to five minutes. Inspect the result with:

```sql
SELECT reference, status, updated_at
FROM payments
WHERE reference = 'YOUR_PAYMENT_REFERENCE';

SELECT e.event_type, e.from_status, e.to_status, e.source, e.created_at
FROM payment_events e
JOIN payments p ON p.id = e.payment_id
WHERE p.reference = 'YOUR_PAYMENT_REFERENCE'
ORDER BY e.created_at;
```

## Refunds

`RefundService.refund(paymentReference, amountKobo, reason, idempotencyKey)` initiates a refund for a successful or partially refunded payment. It locks the payment, checks reserved refund amounts, validates idempotent replays, and sends the refund request to Paystack.

Pending, processing, and successful refunds count toward the reserved amount. The payment's refunded total changes when the initial provider response confirms a processed refund. The same idempotency key and request return the existing refund.

There is currently no refund REST controller, so the Postman collection cannot call this service. Refund completion webhooks and refund reconciliation are also not implemented: a refund returned as processing will remain in that local state until completion handling is added. The scheduled reconciliation job checks payments only.

## Error responses

API errors include `timestamp`, `status`, `error`, and `message`.

| Error | HTTP status |
| --- | --- |
| `ORDER_NOT_FOUND` | 404 |
| `PAYMENT_ALREADY_SUCCESSFUL` | 409 |
| `AMOUNT_MISMATCH` | 422 |
| `PROVIDER_UNAVAILABLE` | 503 |
| `REFUND_LIMIT_EXCEEDED` | 409 |
| Generic invalid arguments | 400 |
| Generic invalid state | 409 |

Invalid or missing webhook signatures return 401.

## Tests

The database test configuration uses Testcontainers with PostgreSQL. Start Docker and supply the Paystack secret environment variable before running:

```bash
mvn test
```

## Current limitations

- Security configuration permits every request, and the test-order endpoint is available without authentication. Add access control before deploying publicly.
- Payment reconciliation applies successful verification results. It does not transition failed provider payments to a local failed state.
- Reconciliation exceptions are currently swallowed; logging, metrics, and alerts need implementation.
- Refund initiation performs the provider call inside the database transaction. If Paystack accepts the refund but the local transaction fails, reconciliation is needed to resolve the outcome before retrying.
- Timestamps use PostgreSQL `TIMESTAMPTZ` and Java `Instant`. A `+00:00` or `Z` suffix indicates UTC; Lagos time is UTC+1.
