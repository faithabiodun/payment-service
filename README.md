# Payment Service

Java 21 and Spring Boot 4.1.1 payment API for Paystack, backed by PostgreSQL. It supports signed payment/refund webhooks, idempotent payment initialization and refunds, payment settlement, refund accounting, and scheduled payment/refund reconciliation.

Amounts are in **kobo**: `500000` means NGN 5,000.

## Run with Docker Compose

Docker Compose starts PostgreSQL and the application. Flyway applies the database migrations during application startup.

PowerShell:

```powershell
$env:DB_PASSWORD = "local-db-password"
$env:PAYSTACK_SECRET_KEY = "sk_test_your_paystack_test_secret"
docker compose up --build
```

Bash:

```bash
export DB_PASSWORD="local-db-password"
export PAYSTACK_SECRET_KEY="sk_test_your_paystack_test_secret"
docker compose up --build
```

The API is available at `http://localhost:8080`; health is at `/actuator/health`. PostgreSQL is available to host tools on port `5433`; inside Compose the app connects to `db:5432`. Data is kept in the `payment-service-postgres` volume. The application container runs as a non-root user. Do not use a live Paystack secret for local development.

To run without Compose, provide `DB_URL` (default `jdbc:postgresql://localhost:5432/payment_service`), `DB_USERNAME` (default `postgres`), `DB_PASSWORD`, and `PAYSTACK_SECRET_KEY`, then run `mvn spring-boot:run` or `bash ./mvnw spring-boot:run`.

## API

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/v1/test-orders` | Create a development order |
| POST | `/api/v1/payments/initialize` | Reserve and initialize Paystack checkout |
| POST | `/api/v1/payments/{reference}/verify` | Verify payment with Paystack |
| GET | `/api/v1/payments/{reference}` | Read payment state |
| POST | `/api/v1/payments/{paymentReference}/refunds` | Create an idempotent refund request |
| GET | `/api/v1/payments/callback?reference=...` | Handle checkout redirect (does not settle) |
| POST | `/webhooks/paystack` | Receive signed Paystack webhooks |

Create an order:

```json
{
  "email": "sam@example.test",
  "amountKobo": 500000
}
```

Initialize using the returned order ID and a unique `Idempotency-Key`:

```json
{
  "orderId": 1
}
```

The amount comes from the stored order. Complete checkout with the returned `authorizationUrl`, then wait for a Paystack webhook or call the verification endpoint. A callback redirect alone never marks a payment successful.

For refunds, the payment must already be `SUCCESS` or `PARTIALLY_REFUNDED`. Send a positive `amountKobo` and reason, plus a unique `Idempotency-Key`. To refund the remaining amount, omit `amountKobo` or send `null`. A real Paystack test transaction is required for provider-backed refund testing.

## Postman and sample data

Import [test-orders.postman_collection.json](test-orders.postman_collection.json). It includes create order, initialize, verify, signed local webhook simulation, partial refund, callback, and payment status requests. Set the `baseUrl` collection variable if needed. The webhook simulation also needs a Postman environment variable named `paystackSecretKey` matching `PAYSTACK_SECRET_KEY`.

Copyable request bodies, a sample checkout sequence, reconciliation SQL, and safe local testing notes are in [docs/testing-samples.md](docs/testing-samples.md).

The simulated success webhook only changes local state; it does not create a Paystack transaction. It is useful for webhook handling and settlement checks, but cannot be verified or refunded by Paystack.

## Reconciliation

Every five minutes, separate payment and refund jobs select bounded batches of stale records. Paystack requests run outside database transactions. Successful payments use the shared settlement service; refund results use the shared refund state service. ShedLock with PostgreSQL database time prevents multiple application instances from running the same job concurrently.

Default timings are configurable through environment variables:

| Variable | Default |
| --- | --- |
| `RECONCILIATION_PAYMENT_STALE_AFTER` | `PT10M` |
| `RECONCILIATION_ABANDON_AFTER` | `PT24H` |
| `RECONCILIATION_REFUND_STALE_AFTER` | `PT10M` |
| `RECONCILIATION_MAX_ATTEMPTS` | `5` |
| `RECONCILIATION_BATCH_SIZE` | `100` |

Reconciliation history is operational data in `reconciliation_attempts`; financial events remain in `payment_events`. Unknown or contradictory provider states are sent for manual review. Refund reservations without a provider refund ID are discovered by the local merchant note; they are never blindly resubmitted.

## CI/CD

`.github/workflows/ci-cd.yml` runs the complete Maven verification suite on pull requests and pushes to `main`, including PostgreSQL Testcontainers and WireMock tests, then builds the Docker image. Pushes to `main` and `v*` tags publish images to GitHub Container Registry as `ghcr.io/<owner>/<repository>` using `GITHUB_TOKEN`.

## Tests

Run the full suite with Docker available:

```bash
bash ./mvnw --batch-mode verify
```

The integration tests use Testcontainers by default. To run them against a local PostgreSQL test database instead, set `TEST_POSTGRES_CONTAINER=false` and provide `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`. Use an isolated disposable database; Flyway creates the schema.

## Operational notes

- `PaymentEventSource.RECONCILIATION` identifies payment successes recovered by reconciliation.
- Payments and refunds with `manual_review_required = true` need an operator to investigate.
- `/api/v1/test-orders` and the current security configuration are development conveniences; add appropriate access control before exposing the service publicly.
- Never store real credentials in source control. Compose reads a local `.env` file automatically if present; `.env` is ignored by Git.
