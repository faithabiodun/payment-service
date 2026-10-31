# Phase 2 Backend Guide

This guide records implementation decisions against the current repository. Checkpoints are appended as they are implemented.

## Checkpoint 4 — Flutterwave V3 with hosted checkout

### Why this adapter uses V3 for the supplied keys

The supplied dashboard credentials are V3 public/secret/encryption keys. They are not V4 OAuth client credentials. This repository also has no browser-side payment-method tokenization flow. Flutterwave V3 Standard fits the current backend boundary: the server sends a reference, currency, amount and customer email to `POST /payments`, then returns Flutterwave's hosted `data.link`.

Official references:

- [Flutterwave Standard hosted checkout](https://developer.flutterwave.com/docs/flutterwave-standard-1)
- [V3 verify by merchant reference](https://developer.flutterwave.com/reference/verify-transaction-with-tx_ref)
- [V3 refunds and status meanings](https://developer.flutterwave.com/docs/refunds)
- [V3 webhook authentication](https://developer.flutterwave.com/v3.0.0/docs/webhooks)

The separate V4 types and verifier stay explicitly named `FlutterwaveProperties` and `FlutterwaveWebhookVerifier`; the V3 adapter does not reuse V4 auth or signature logic.

### Main path in this repository

```text
PaymentController
  -> PaymentService
  -> PaymentCommandService.reserve(..., provider)
       TX: persist payment attempt and sticky provider; COMMIT
  -> PaymentProviderRegistry.get(provider).initialize(...)
       NO TX: POST hosted checkout request
  -> PaymentCommandService.markInitialized(...)
       TX: persist checkout URL; COMMIT

Flutterwave webhook
  -> FlutterwaveWebhookController
       verify V3 verif-hash before parsing
  -> FlutterwaveWebhookService
       NO TX: GET verify_by_reference; check reference and provider transaction ID
  -> FlutterwaveChargeWebhookProcessor
       TX: claim event + PaymentSettlementService.settleSuccess; COMMIT

Flutterwave refund event (when enabled in the dashboard)
  -> authenticate with the same V3 verif-hash boundary
  -> use data.id only to find the local refund
  -> NO TX: GET /refunds/{id}; ignore event status and amount
  -> FlutterwaveRefundWebhookProcessor
       TX: claim event + RefundStateService.applyVerifiedState; COMMIT
```

The provider selection is now optional in `InitializePaymentRequest.provider`. Omitting it preserves the Phase 1 default, Paystack. Once reserved, the selected provider is saved on `Payment` and is not changed after a timeout.

### Refund path

`RefundService` reserves the local refund first, then invokes `RefundProviderRegistry` using the payment's persisted provider. Flutterwave's V3 refund request uses the saved provider transaction ID and a local refund marker in `comments`. The local idempotency key still protects our API. Flutterwave V3 does not document the V4 `X-Idempotency-Key` behavior, so the adapter does not invent or send that V4 header.

Flutterwave V3's refund create response has a refund `data.id` and a separate `flw_ref`. The adapter stores `data.id` because the documented fetch endpoint uses that refund ID. Flutterwave V3's `completed` status means the refund has been initiated and is still pending disbursement. The adapter maps it to `ProviderRefundState.PROCESSING`; only completed disbursement statuses map to `SUCCEEDED`.

### Money conversion

The domain stores integer minor units. `FlutterwaveV3PaymentProvider` converts to the provider's decimal major-unit amount using `Currency.getDefaultFractionDigits()` and converts the verified response back with exact arithmetic. NGN `500000` kobo is sent as `5000.00` naira. A malformed or excess-precision provider amount is rejected instead of rounded silently.

### Failures and duplicates

- A create-payment/refund timeout is ambiguous. The local reservation remains; the code does not send a second request automatically.
- A duplicate authenticated charge webhook may cause another verification GET, but its event claim and financial settlement are atomic in `FlutterwaveChargeWebhookProcessor`, so it has one settlement effect.
- A duplicate authenticated refund webhook may cause another verification GET, but its event claim and refund state update are atomic in `FlutterwaveRefundWebhookProcessor`.
- An invalid `verif-hash` is rejected before parsing or database access.
- A signed webhook is only a signal. `FlutterwaveWebhookService` re-queries Flutterwave and settlement validates reference, amount and currency.
- A provider/network mismatch is an error; no local payment amount is taken from the browser or webhook.

### Credentials

Local credentials live in the gitignored `.env`; `spring.config.import` loads that local properties file. `application.yaml` contains only environment variable names. The V3 webhook secret hash is configured separately in Flutterwave's dashboard and must be provided as `FLUTTERWAVE_V3_WEBHOOK_SECRET_HASH`. Do not substitute the API secret key for this webhook hash.

### Tests and exercise

`FlutterwaveV3ProviderWireMockTests` covers hosted link creation, minor/major amount conversion, transaction verification, refund initiation/fetch, and recovery after a lost refund response. `FlutterwaveV3PaymentProviderMappingTests` checks unknown statuses remain unknown. `FlutterwaveV3WebhookVerifierTests` checks V3 header authentication. `FlutterwaveWebhookServiceTests` checks provider re-query and duplicate settlement protection for charges and refunds.

**Exercise:** Why does the webhook handler call Flutterwave again after the signature succeeds?

**Solution:** A signature proves the message came through the configured webhook secret. It does not prove the payment still matches this order's expected amount, currency and merchant reference. The server-side verification call checks those values before shared settlement runs.

### What you should be able to explain as a backend developer

The Flutterwave adapter translates its API response into our normalized provider types. Local payment/refund reservations commit before provider HTTP calls. The chosen provider stays attached to the attempt. Webhooks are authenticated, verified against provider truth, and deduplicated inside the same transaction as settlement. Flutterwave V3's `completed` refund state is not treated as final disbursement success.

### Current limitations

Flutterwave documents that V3 refund webhooks must be enabled for the account. The handler accepts event names beginning with `refund` and treats `data.id` only as a local lookup hint; it fetches current refund state from the provider before applying it. Durable webhook inbox processing is the later Checkpoint 7. A real webhook cannot authenticate until the independent dashboard webhook hash is configured.

## Checkpoint 5 — Stripe with hosted Checkout

### Why this adapter uses Checkout Sessions

The application already returns a hosted checkout URL for Paystack and Flutterwave. Stripe Checkout Sessions keep that client experience consistent: our server creates a one-time `mode=payment` session and returns the Stripe-hosted URL. Stripe Checkout manages card collection and issuer authentication such as 3DS. We do not collect card numbers or CVVs.

A Checkout Session is the hosted checkout attempt. Its underlying PaymentIntent is Stripe's payment lifecycle record. A PaymentIntent can be `requires_action`, `processing`, `succeeded`, or another state. We store the Checkout Session ID during initialization and the PaymentIntent ID after successful verification. The browser return remains informational; it never settles the payment.

Stripe's current hosted Checkout docs describe redirecting the customer to the URL returned by the Session API and listening for `checkout.session.completed` and asynchronous payment events on the server. The official Java SDK README currently lists `stripe-java` 34.1.0 and Java 21 support. Its `Webhook.constructEvent` method authenticates the payload using Stripe's signature header and endpoint secret.

Official sources checked for this checkpoint:

- [Hosted Checkout and fulfillment](https://docs.stripe.com/payments/existing-customers?platform=web&ui=stripe-hosted)
- [Stripe idempotent requests](https://docs.stripe.com/api/idempotent_requests)
- [Official Stripe Java SDK and current version](https://github.com/stripe/stripe-java)
- [Stripe Java webhook signature verification implementation](https://github.com/stripe/stripe-java/blob/master/src/main/java/com/stripe/net/Webhook.java)

The SDK provides typed Checkout, PaymentIntent and Refund operations and the official signature verifier. This is why this checkpoint adds the SDK instead of writing a separate Stripe HTTP client and signature algorithm. `StripeClientConfiguration` disables hidden network retries so uncertain operations remain visible to our services; Stripe POST requests still receive the operation idempotency key.

`ProviderReadiness` is a small preflight port shared by payment and refund providers. `PaymentService` and `RefundService` call it before saving a new local reservation. Stripe refuses the operation when `STRIPE_SECRET_KEY` is blank, so missing credentials do not leave a newly reserved payment/refund in a misleading in-progress state. The SDK client uses a nonfunctional placeholder only so the application can start when Stripe is not configured; the adapter's preflight prevents that placeholder from making an API request.

### Main files and responsibilities

- `StripePaymentProvider` implements `PaymentProvider` and `RefundProvider`. It builds a hosted Checkout Session from the authoritative `ProviderPaymentRequest`, retrieves the Session/PaymentIntent to verify current provider truth, creates idempotent refunds, and maps Stripe states to normalized states.
- `StripeProperties` and `application.yaml` keep the API key, webhook signing secret, API base and redirect URLs in environment configuration.
- `StripeClientConfiguration` builds the SDK client once through Spring dependency injection.
- `StripeWebhookController` accepts raw bytes and `Stripe-Signature`. It returns `400` for a signature failure.
- `StripeWebhookVerifier` calls Stripe's SDK `Webhook.constructEvent(...)`. The SDK verifies the raw body before the event object is acted on.
- `StripeWebhookService` allow-lists Checkout completion and refund events, then asks the Stripe adapter to re-query the relevant Session/Refund.
- `StripePaymentWebhookProcessor` and `StripeRefundWebhookProcessor` claim Stripe's real event ID and call the existing `PaymentSettlementService` or `RefundStateService` in one transaction.
- `PaymentService.verify` uses the stored Checkout Session ID before settlement; after settlement it uses the stored PaymentIntent ID.
- `SecurityConfig` allows the public Stripe webhook route. Public reachability is safe only because the endpoint verifies Stripe's cryptographic signature.

### Architecture and transactions

```text
PaymentService
  -> PaymentProviderRegistry
  -> StripePaymentProvider.initialize
       Stripe Checkout Session API (outside TX)
       -> hosted URL + Session ID

Stripe webhook POST
  -> StripeWebhookController (raw body + Stripe-Signature)
  -> StripeWebhookVerifier (official SDK authentication)
  -> StripeWebhookService (retrieve current Stripe state outside TX)
  -> StripePaymentWebhookProcessor
       TX: deduplicate Stripe event ID + shared payment settlement; COMMIT
```

```text
TX 1: PaymentCommandService.reserve(order, idempotency key, STRIPE); COMMIT
NO TX: StripePaymentProvider creates Checkout Session with Stripe idempotency key
TX 2: PaymentCommandService.markInitialized stores checkout URL and Session ID; COMMIT

NO TX: Stripe webhook signature check and Stripe API verification
TX: claim event + lock payment/refund + apply shared domain state; COMMIT
```

There is no new database migration. The existing `payments.access_code` persistence path stores `ProviderPaymentResult.providerSessionId` during initialization. For Stripe that value is the Checkout Session ID (`cs_...`). Once payment succeeds, shared settlement stores the PaymentIntent ID (`pi_...`) as `provider_transaction_id`. This preserves both Stripe identities without confusing a checkout page/session with the financial transaction.

### State translation and verification

`StripePaymentProvider.paymentState` maps `requires_action` to `REQUIRES_ACTION`, `processing` to `PROCESSING`, `succeeded` to `SUCCEEDED`, `canceled` to `CANCELLED`, and payment-method/confirmation states to `PENDING`. Unknown future values remain `UNKNOWN`.

The webhook payload is only a notification. For a Checkout event, the service takes its Session ID and the local reference, then retrieves the current Session and PaymentIntent from Stripe. Shared settlement still checks the stored payment owner, local reference, exact integer minor-unit amount, currency and transaction ID. A paid Session without a verified PaymentIntent is left `UNKNOWN` rather than treated as a successful financial transaction.

Refund requests use the persisted PaymentIntent ID and carry a separate local refund reference in Stripe metadata. The `Idempotency-Key` reaches Stripe's refund POST. Refund status is verified by fetching the Stripe Refund; when create-response recovery is needed, the adapter searches by PaymentIntent and matches the local metadata marker. `requires_action` maps to `NEEDS_ATTENTION`; unrecognized refund statuses remain `UNKNOWN`.

### Failure cases and limits

- A timeout during Checkout Session creation does not cause a second provider attempt or switch providers. The payment remains owned by Stripe for reconciliation.
- A duplicate Stripe webhook has a stable `evt_...` event ID. The processor claims it transactionally; shared settlement also prevents a second financial effect if Stripe sends another event ID for the same payment.
- Invalid/missing `Stripe-Signature` or an unset `STRIPE_WEBHOOK_SECRET` is rejected before service processing.
- `checkout.session.completed` can still represent an unpaid delayed-method Session. The adapter checks current Stripe state and settles only `succeeded`; Stripe's later asynchronous-success event has a distinct event ID and can settle then.
- Provider HTTP failures are classified in Checkpoint 9: permanent 4xx/auth responses are not retried, while safe read timeouts and transient server/rate-limit responses can be retried.
- Checkpoint 7 adds a PostgreSQL inbox: authenticated payloads are persisted before the controllers return `200`, then a leased worker verifies and applies their business effect.
- Payment reconciliation is still Paystack-specific in this repository. Provider-aware reconciliation is checkpoint 10.
- Stripe credentials are configured locally through `STRIPE_SECRET_KEY` and `STRIPE_WEBHOOK_SECRET` in the ignored `.env`. Automated adapter tests still use WireMock and local signatures so they are deterministic and never charge a real account. Never commit either value.

### Tests and exercise

`StripePaymentProviderWireMockTests` verifies hosted Session creation, amount/currency/reference fields, Stripe idempotency headers, Session-to-PaymentIntent verification, refund creation, refund retrieval and refund discovery. `StripeStateMappingTests` verifies lifecycle mappings and unknown values. `StripeWebhookServiceTests` proves invalid signatures do not reach the handler and a valid Checkout event is re-verified before it reaches the shared payment processor.

## Checkpoint 6 — Provider routing

### What problem does routing solve?

One payment attempt must have one provider owner. If a request to Stripe times out after Stripe may have accepted it, changing that same attempt to Paystack could create a second charge. Keep the attempt attached to Stripe and investigate with Stripe. A deliberate new attempt can choose another provider only after confirming the previous attempt did not succeed.

### Routing policy and current behavior

`InitializePaymentRequest.provider` is optional. `ConfiguredProviderRoutingPolicy` uses the requested provider when present; otherwise it uses `payment.routing.default-provider`, configured from `PAYMENT_DEFAULT_PROVIDER` and defaulting to `PAYSTACK` for backwards compatibility. This is deterministic and deliberately simple; it does not silently switch providers after a timeout.

`PaymentService.initialize` selects and checks the provider before calling `PaymentCommandService.reserve`. The reservation writes the selected provider to `Payment.provider` in the same database transaction as the attempt. `PaymentService` then calls the provider outside that transaction. Later verification and webhooks use the stored provider, not a fresh routing decision.

```text
TX 1: reserve order + local reference + idempotency key + provider; COMMIT
NO TX: initialize at the selected provider
TX 2: save provider checkout URL/session identity; COMMIT
```

Reusing an idempotency key with a different provider is rejected by `PaymentCommandService`. Repeat the same provider choice for retries. To deliberately try another provider, use a new idempotency key and create a new attempt after confirming the first attempt did not succeed.

### Credentials and testing

`application.yaml` maps Stripe's API key and webhook signing key from `STRIPE_SECRET_KEY` and `STRIPE_WEBHOOK_SECRET`. Both variable names are present in the ignored local `.env`; values must not be logged, copied into source, or committed. Routing unit tests need no network credentials. Provider WireMock tests use fake keys and a local HTTP server, so they do not make a real charge.

**Exercise:** Stripe initialization times out after sending a request. Should the service retry the same attempt through Flutterwave?

**Solution:** No. Stripe may have created a Checkout Session. Keep the attempt owned by Stripe and reconcile with Stripe. If a new attempt is appropriate, create it with a new idempotency key and explicit provider.

### What you should be able to explain as a backend developer

Routing chooses a provider before the external call and persists that choice with the attempt. A network timeout is ambiguous, so it does not authorize provider failover. Idempotency prevents the same local operation from being reused with a different provider, while a genuinely new attempt has its own identity.

## Checkpoint 7 — Durable webhook inbox

### What problem does an inbox solve?

The provider owns delivery, while our database owns payment state. If an HTTP handler does all the work before responding, provider API calls can exceed webhook timeouts. If it responds first and only queues work in memory, a process crash can lose the message. The inbox stores the authenticated message in PostgreSQL before acknowledging it.

### Current flow and files

Each controller keeps its provider's authentication protocol: Paystack HMAC, Flutterwave's configured `verif-hash`, and Stripe's official raw-body verifier. Only after authentication does it call `WebhookInboxService.accept`. `ProviderWebhookEnvelopeParser` extracts event metadata and computes a stable deduplication key. `WebhookInboxRepository` stores the raw JSON text with provider, type and processing metadata. `WebhookInboxWorker` claims rows in batches, invokes the existing provider service, and retries failures.

```text
NO TX: provider-specific signature verification over raw body
TX: insert (provider, dedup key, raw payload); COMMIT
return 200

one SQL claim: SELECT ... FOR UPDATE SKIP LOCKED + lease; COMMIT
NO TX: provider verification and normalization where needed
TX: existing provider event claim + settlement/refund transition; COMMIT
SQL update: mark inbox processed, or schedule retry
```

The final inbox status update is separate from settlement so a crash can replay the event. Existing event processors deduplicate the financial effect in the same transaction as settlement/refund application. A replay can therefore finish the inbox bookkeeping without settling or refunding twice. Attempt counts fence stale workers; leases recover rows left in `PROCESSING` after a crash.

### Event identity and duplicates

Stripe supplies a stable `evt_...` event ID, which is stored. Flutterwave's current webhook guide shows deduplication by event type, `data.id`, and `data.status`; this app uses that combination and does not label it a provider event ID. Paystack's webhook payload identifies the transaction/refund object; this app deduplicates by event type, data ID and status. When a signed payload has no object ID/status, a SHA-256 of its exact body provides repeat-delivery deduplication. The hash is our key, not an ID claimed to be supplied by the provider.

`V9__create_webhook_inbox.sql` adds a constrained provider/status table with unique `(provider, deduplication_key)` and a partial ready-row index. The worker uses PostgreSQL `SKIP LOCKED` so concurrent instances claim different rows. After eight failed attempts, or an expired lease at the attempt limit, the row moves to `DEAD_LETTER`. The raw payload can contain customer details, so restrict database access and define an appropriate retention policy before production.

### Failure cases

- Invalid signatures are rejected before storage.
- Duplicate delivery hits the unique key and is acknowledged without adding another row.
- A crash after `200` leaves a recoverable row.
- A worker crash leaves a lease that expires and makes the row claimable again.
- Provider API timeouts are retried with exponential delay and jitter. The existing provider-specific verification and domain idempotency remain in force.
- Unknown event types are safely acknowledged after durable storage; dispatch treats them as no-op events.

The design follows the providers' own webhook guidance to respond quickly, verify critical Flutterwave transaction values, and tolerate duplicate deliveries: [Paystack Webhooks](https://paystack.com/docs/payments/webhooks/), [Flutterwave Webhooks](https://developer.flutterwave.com/docs/webhooks), [Paystack Webhook Events API](https://paystack.com/docs/api/webhook-events/).

**Exercise:** Why is saving an event ID in memory before returning `200` insufficient?

**Solution:** Memory disappears on process restart. A database commit before the response makes the event recoverable after a crash.

### What you should be able to explain as a backend developer

Webhook delivery is at least once, so I persist the authenticated raw event before acknowledging it, use provider-specific stable deduplication material, claim work with leases and `SKIP LOCKED`, and make the financial effect idempotent. This provides exactly-once business effect without claiming exactly-once delivery.

## Checkpoint 8 — Transactional outbox

### What problem does the outbox solve?

The dual-write problem occurs when one operation writes PostgreSQL and separately sends a message. A crash between those writes leaves one side missing. The outbox writes an event row in the same transaction as the successful payment or refund transition. A separate worker later dispatches it.

### Current flow and files

`PaymentSettlementService` now appends `PAYMENT_SUCCEEDED` through `OutboxEventStore` after updating the payment/order and audit history. `RefundStateService` appends `REFUND_*` events for state changes. Both are already transactional services, so the outbox INSERT participates in the same PostgreSQL transaction. `OutboxRepository` claims rows with `SKIP LOCKED` and expiring leases. `OutboxWorker` retries delivery failures with exponential delay and jitter, then dead-letters after eight attempts.

```text
TX: payment/refund state transition
  + payment audit history
  + INSERT outbox_events
COMMIT

worker: claim lease -> OutboxSink.dispatch -> mark PUBLISHED
```

`OutboxSink` is the adapter boundary for email, a queue, or another service. The current `LoggingOutboxSink` only emits a structured event summary because this application has no email or broker integration yet; it does not claim to send a customer notification. A real sink should use the outbox row ID as its idempotency key because delivery can repeat if a process dies after dispatch but before marking the row published.

`V10__create_outbox.sql` adds a JSONB payload, constrained status, retry metadata, a lease, and a partial index for rows ready to dispatch. Outbox payloads contain only the minimum domain values needed downstream and omit customer PII.

**Exercise:** What happens if dispatch succeeds, then the process crashes before `PUBLISHED` is saved?

**Solution:** The lease expires and the row is dispatched again. The downstream sink/consumer must deduplicate by the stable outbox row ID.

### What you should be able to explain as a backend developer

The payment and its outbox event commit atomically, so downstream work cannot be silently lost after a committed payment. The worker is at least once, not exactly once; consumers need idempotency. The outbox is a database pattern, not a message broker.

## Checkpoint 9 — Provider resilience

### What problem does resilience solve?

External providers can be slow, unavailable, or rate-limited. A timeout means our caller stopped waiting; it does not prove the provider did not process a request. Retry behavior must depend on both the HTTP result and the operation type.

### Current policy and code

`ProviderFailureClassifier` recognizes network-level exceptions, HTTP `429`, and `5xx` as transient. A `400`, `401`, or `403` is treated as permanent and is not retried. `ProviderCallExecutor` applies Resilience4j CircuitBreaker, Retry, and semaphore Bulkhead to calls returned from `PaymentProviderRegistry` and `RefundProviderRegistry` through small adapter decorators.

- GET-style verification/refund lookup operations may retry up to three attempts with exponential backoff and random jitter.
- Payment initialization and refund creation are money-moving POSTs and are never retried automatically in this implementation.
- The circuit opens after enough transient failures, waits 30 seconds, then allows two half-open probes.
- The bulkhead caps concurrent provider operations at 20 per provider and fails fast when saturated.
- Paystack and Flutterwave WebClient calls have a 10-second request wait. Stripe has a 5-second connect timeout, 10-second read timeout, and SDK automatic retries disabled so retry behavior remains visible.

The retry order is significant: the Retry wrapper runs inside the circuit breaker, so three GET attempts count as one caller-level provider failure. Only the final transient failure contributes to opening the circuit. A circuit-open check happens before payment/refund reservation when the provider is already known to be unavailable.

### Provider idempotency limits

Stripe uses its provider idempotency key on payment/refund creation, but this code still does not automatically retry money-moving POSTs. Stripe API v1 stores the first endpoint result for a key and returns the same result for retries; our explicit policy remains conservative until operation-level recovery is designed. Flutterwave V4 documents `X-Idempotency-Key`, but this codebase's configured Flutterwave adapter is V3 and does not claim V4 semantics. Paystack create/refund calls also are not retried by this policy. The local idempotency keys protect our own operation identity; they do not prove that a provider accepted or rejected an ambiguous request.

The dependency is Resilience4j 2.4.0, using its core CircuitBreaker, Retry, and Bulkhead modules. Its 2.4.0 release includes Spring Boot 4 support, but this service uses the programmatic core modules rather than the Spring AOP starter: that keeps retry boundaries explicit around provider ports and avoids annotation-based retries accidentally wrapping money-moving methods. [Resilience4j 2.4.0 release](https://github.com/resilience4j/resilience4j/releases/tag/v2.4.0), [Retry configuration](https://resilience4j.readme.io/docs/retry), [Stripe idempotency](https://docs.stripe.com/api/idempotent_requests), [Flutterwave V4 request headers](https://developer.flutterwave.com/v4.0/docs/api-headers).

### Failure cases

- `400` invalid request: no retry; fix the request.
- `401`/`403`: no blind retry; credentials or scopes need attention.
- `429`: safe read requests back off with jitter; money-moving requests are not replayed.
- `500`/connection reset/timeout: safe reads retry; ambiguous money POSTs remain attached to their provider for reconciliation.
- Repeated transient failures: the provider circuit opens, preventing new calls until probes are allowed.
- Bulkhead full: fail fast rather than allowing provider calls to consume every request thread.

**Exercise:** A refund POST times out, and the provider may have accepted it. What should the service do?

**Solution:** Do not submit the refund again as a new request. Keep the local refund reservation and query/reconcile with the same provider. Provider idempotency can make a same-operation retry safer only when that provider's current API contract and the stable operation key are confirmed.

### What you should be able to explain as a backend developer

A retry repeats a technical read, not a financial decision. The provider classifier separates transient failures from permanent input/auth errors. Circuit breakers stop repeated calls during outages, and bulkheads limit concurrent work. An ambiguous money POST is preserved for provider-specific reconciliation rather than failed over or blindly repeated.

## Checkpoint 10 — Multi-provider reconciliation

### What problem does reconciliation solve?

Webhooks can be delayed, duplicated, or missed. Reconciliation is a scheduled read against the provider that owns a payment/refund. It compares the provider's current result with our local record and either applies a safe state transition or records a discrepancy for manual review.

### Current flow and files

`PaymentReconciler` now reads `Payment.provider` and calls only `PaymentProviderRegistry.get(provider).verify(...)`. Before settlement it prefers the provider transaction ID; while checkout is still open it can use the stored checkout/session identity. `RefundReconciler` follows `Refund.provider` and uses `RefundProviderRegistry` for verify or discovery. Both use normalized `VerifiedPayment` / `VerifiedRefund`, then call shared settlement/refund state services.

```text
NO TX: query the saved provider through its registry adapter
compare reference/provider/state/amount/currency
TX: lock payment/refund -> apply safe state or flag review -> record history -> COMMIT
```

`ReconciliationJob` still uses ShedLock to avoid multiple application instances running the same scheduled batch. Worker-level row claiming is separate; the inbox and outbox use `SKIP LOCKED` instead. `ReconciliationAttemptStore` preserves each attempt. Migration `V11__classify_reconciliation_discrepancies.sql` adds a constrained `discrepancy_type` column, with categories such as `AMOUNT_MISMATCH`, `CURRENCY_MISMATCH`, `UNKNOWN_PROVIDER_STATE`, `LOCAL_PROVIDER_CONTRADICTION`, and `DUPLICATE_PROVIDER_TRANSACTION`.

The code never queries all three providers for one attempt. A failed, cancelled, pending, or unknown result that contradicts a local success goes to manual review. A successful provider result is passed through `PaymentSettlementService`, which independently checks owner, local reference, amount and currency under a row lock. Refunds use the same `RefundStateService` invariants and do not resubmit a refund when a provider result is missing.

This repository does not yet import provider settlement reports or payout files, so it cannot reconcile a merchant bank settlement total. It reconciles transaction/refund state only; a settlement-level comparison requires a real provider report/API and a defined ledger clearing-account model.

**Exercise:** A `STRIPE` payment has an ambiguous timeout. Should reconciliation query Flutterwave too?

**Solution:** No. The provider was persisted on the payment attempt before the request. Reconciliation queries Stripe and records any conflict; only a new payment attempt can deliberately select another provider.

### What you should be able to explain as a backend developer

Reconciliation repairs local state from the provider attached to each attempt. It uses normalized results and shared locked transitions. Matching truth can be applied once; contradictory truth is recorded and escalated instead of guessed. The attempt history supports audit and investigation.

## Checkpoint 11 — Immutable ledger foundation

### Accounting from first principles

A ledger records where financial value came from and where it went. A **journal entry** groups one business event. Its **postings** assign amounts to named accounts. A debit and a credit are the two sides of the entry; neither word means “good” or “bad.” The balancing rule is that total debit equals total credit in one currency. Account type and normal balance determine how to read its balance.

For a customer paying ₦5,000 (`500,000` kobo), the example journal is:

| Account | Debit | Credit |
| --- | ---: | ---: |
| Provider Clearing — selected provider | 500,000 | 0 |
| Sales Revenue | 0 | 500,000 |

If the provider reports a ₦75 fee (`7,500` kobo), add a balanced pair:

| Account | Debit | Credit |
| --- | ---: | ---: |
| Payment Processing Fee Expense | 7,500 | 0 |
| Provider Clearing — selected provider | 0 | 7,500 |

The merchant's clearing receivable is then 492,500 kobo. A ₦2,500 refund (`250,000` kobo) posts a debit to Sales Returns and a credit to the same provider clearing account. This example treats the order amount as recognized sales; tax, inventory, settlement bank accounts, and processor fee reimbursement are outside the current domain model.

### Current implementation and transaction boundary

`LedgerPostingService` creates a payment journal in the same `@Transactional` method as `PaymentSettlementService` updates payment/order state, audit history, and outbox. It creates a refund journal in the same `RefundStateService` transaction that marks the refund successful and increments the refunded amount.

```text
TX: lock payment/refund
  -> validate provider truth + financial invariant
  -> update payment/order or refund
  -> append audit and outbox records
  -> insert journal entry + balanced postings
COMMIT all together
```

`V12__create_immutable_ledger.sql` seeds provider-specific clearing accounts plus sales revenue, returns, and processing-fee expense accounts. Database deferred constraint triggers reject journals with fewer than two postings, multiple currencies, or unequal debits and credits at transaction commit. Update/delete triggers reject edits to posted journals and postings. Corrections must be new reversing/correcting journals. `LedgerBalanceQuery` derives net debit as `SUM(debit - credit)`; no mutable balance column is treated as the source of truth.

The journal `event_key` is unique (`PAYMENT_SUCCESS:<reference>` or `REFUND_SUCCESS:<reference>`), so replay cannot add a second ledger effect. The existing payment/refund row locks and refund-capacity check remain the primary protection against concurrent money movement; the ledger is the immutable accounting trail.

**Exercise:** A journal has debit postings totaling 507,500 kobo and credit postings totaling 500,000 kobo. Is it valid?

**Solution:** No. It is short 7,500 credit. If that debit represents the fee expense, the matching credit must reduce Provider Clearing by 7,500.

### What you should be able to explain as a backend developer

Operational status says whether a payment/refund is pending or complete; the ledger records its balanced financial movements. A successful state change and its journal commit atomically. Posted financial history is append-only; an error is corrected with a new journal, and balances are derived from postings.

**Exercise:** Why does the local payment store both `cs_...` and `pi_...` identifiers?

**Solution:** The Checkout Session ID identifies the hosted checkout used to retrieve the attempt before payment completes. The PaymentIntent ID identifies Stripe's payment lifecycle and is the correct reference for settlement/refunds. They describe related but different objects.

### What you should be able to explain as a backend developer

Stripe Checkout is the customer-facing hosted page; the PaymentIntent is the underlying financial lifecycle. The adapter maps Stripe's richer states into our neutral payment states. The raw webhook is authenticated with Stripe's SDK and current provider data is re-queried before a shared, locked settlement. The local idempotency key protects our API, while sending that key to Stripe protects Stripe's POST operation from duplicate creation.

## Checkpoint 12 — Production engineering

### What problem are we solving?

The payment and accounting paths are now shared and recoverable, but a real service also needs controlled access, useful operating signals, and a repeatable way to build and run it. A `200 OK` says one HTTP request completed; it does not say payments are settling, webhooks are being processed, or secrets are protected.

### Mental model

```text
customer/admin --credentials--> Spring Security --> API controller
provider ---------------------> signature check --> durable inbox
provider calls --> bounded metrics --> Prometheus --> Grafana
PostgreSQL queues --> backlog gauges -----------^
source commit --> GitHub Actions --> tests --> Docker image
```

### Concepts before implementation

* **Authentication** asks who made the request. **Authorization** asks whether that identity may do this operation. The filter chain in `SecurityConfig` authenticates API requests; method security on `RefundController.refund` separately requires the `ADMIN` role.
* **Least privilege** grants only the access a role needs. A `CUSTOMER` can call authenticated payment APIs, but cannot initiate a refund; refund access belongs to an `ADMIN` for this initial bootstrap.
* **Metrics** count and time behavior so operators can detect slow providers or stuck queues. Metric labels must be bounded: provider, operation and outcome have a small fixed set. A payment reference is effectively unlimited distinct values and must not be a metric label.
* **Structured logs** put fields in machine-readable JSON. The `RED` view asks about request rate, error rate, and duration. An **SLI** is a measured reliability signal (for example successful payment verification rate); an **SLO** is the target for that signal over a time window. This repository exposes useful measurements but does not define business-approved SLO targets or paging alerts.
* **Health checks** report whether the process is ready/alive. **CI** builds and checks a change repeatedly; it does not deploy it. The existing workflow creates/publishes images on main/tag pushes, but has no deployment job.
* **A container image** is the built, versionable application package; a container runs that image with environment configuration. PostgreSQL, Prometheus and Grafana keep persistent data in named volumes. Secrets are passed at runtime, never copied into the image.

### Existing files and the change

* `src/main/java/com/faith/pay/config/SecurityConfig.java` previously allowed every request (`anyRequest().permitAll()`). It now uses stateless HTTP Basic and authenticates application APIs. The browser callback is public because it only displays UX text and never settles a payment. Provider webhook routes remain reachable without API credentials because their controllers do provider-specific cryptographic verification before inbox insertion. Health/info are public with no health details. Prometheus is restricted to the `MONITORING` and `ADMIN` roles.
* `src/main/java/com/faith/pay/refund/RefundController.java` uses `@PreAuthorize("hasRole('ADMIN')")`. Authentication alone is not enough to perform a money-moving refund.
* Environment credentials are `API_CUSTOMER_USERNAME`, `API_CUSTOMER_PASSWORD`, `API_ADMIN_USERNAME`, and `API_ADMIN_PASSWORD`. No default password is embedded. If a username is provided without its password (or vice versa), startup fails. With no accounts configured, the API is inaccessible while health and authenticated webhooks continue to work. This is a bootstrap for development/internal API access, not a customer identity or tenant-authorization system.
* `ProviderCallExecutor` records `payment.provider.requests` and `payment.provider.request.duration`, tagged only with provider, operation, and outcome. `WebhookInboxService` counts accepted and duplicate messages. `PaymentQueueMetrics` reads pending and dead-letter counts from PostgreSQL for webhook inbox and outbox queues. Database-backed gauges are sampled when scraped, rather than pretending a process-local counter is durable.
* `CorrelationIdFilter` puts a validated/generated `X-Correlation-ID` in the SLF4J MDC and response. Spring Boot's Logstash JSON formatter includes MDC fields, so the ID appears in structured logs. It is useful for following a request but intentionally is not a metric label. [Spring Boot structured logging](https://docs.spring.io/spring-boot/reference/features/logging.html)
* `pom.xml` adds the runtime `micrometer-registry-prometheus` exporter managed by Spring Boot. `application.yaml` exposes `health`, `info`, and `prometheus`, enables health probes, and sets the application metric tag. `/actuator/prometheus` should only be reachable from the monitoring network in a real deployment.
* `compose.yaml` runs PostgreSQL, the app, Prometheus and Grafana. Compose generates the Prometheus scrape config with its monitoring account; it does not rely on Prometheus to expand arbitrary environment variables in its config file. `SecurityConfig` grants `/actuator/prometheus` to `MONITORING` and `ADMIN`. Configure customer/admin/monitoring credentials and `GRAFANA_ADMIN_PASSWORD` in local `.env`; `.env` is ignored by Git and `.dockerignore` prevents copying it into the image. Provider keys are passed to the app only as runtime environment variables.
* `Dockerfile` builds with Java 21, copies the packaged JAR into a smaller Java runtime image, and runs as the non-root `spring` user.
* `.github/workflows/ci-cd.yml` runs Maven `verify` (including Testcontainers tests) on GitHub's Docker-capable runner and validates the image. On main/tag pushes it publishes to GHCR. It does not automatically deploy. Database changes use Flyway roll-forward migrations; a rollback of application code can fail if it expects an older schema, so migrations should be backward-compatible during rollout.

### Transaction boundaries and failure scenarios

Security checks run before the controller. A webhook route is public at HTTP authentication level because the provider cannot log into our app; its own signature is checked before the inbox transaction commits. The inbox insert still commits before `200` is returned.

Provider metrics are observations around `ProviderCallExecutor.execute`; they do not alter retry policy or financial transaction boundaries. Queue depth metrics issue read-only count queries when Prometheus scrapes. If Prometheus is unavailable, payments and database queue processing continue; only dashboards lose fresh samples. If the DB is unavailable, the application cannot durably accept or process financial work and should report unhealthy.

An API request without credentials receives `401`; an authenticated customer refund request receives `403`. No secret is logged by these changes. The webhook inbox retains raw bodies for recovery, so payload access and retention must be controlled; a retention/redaction policy is still an operational follow-up. Never put raw card data in this service. Use provider-hosted payment collection. Run Basic authentication only over HTTPS, rotate environment credentials, restrict access to Actuator/Prometheus at the network boundary, and use a managed secrets store in deployment. Rate limiting and a durable customer identity/tenant model are not implemented in this repository and should be supplied by an API gateway/identity integration before public multi-customer production use.

### Tests and exercise

`ApiSecurityTests` proves anonymous refund requests get `401`, a `CUSTOMER` cannot perform an admin refund (`403`), and only the monitoring/admin role can scrape Prometheus. `PaymentCallbackSecurityTests` proves the browser callback remains available without API credentials. `ProviderResilienceTests` verifies bounded operation outcomes without exposing references. `WebhookInboxTests` verifies duplicate counting. The existing PostgreSQL tests cover persistent queue behavior and the GitHub workflow runs the full suite with Docker.

**Exercise:** Should a failed Prometheus scrape cause a successful payment transaction to roll back?

**Solution:** No. A scrape is an observability read. Payment state, ledger postings, and outbox records commit according to their own database transaction; monitoring failure affects visibility, not the financial decision.

### What you should be able to explain as a backend developer

Authentication identifies a caller and authorization restricts operations by role. Webhooks use provider signatures rather than customer login. Metrics use bounded labels; queue gauges come from durable database state. Docker packages the app, Compose configures the local operating stack, and CI verifies the same build without deploying it. Secrets arrive through the environment and never belong in Git or image layers.

## What I Can Now Explain as a Backend Engineer

Use these short answers as a starting point; explain them in your own words and point to the files named above.

| Topic | Interview-style answer | Codebase anchor |
|---|---|---|
| Provider-neutral architecture | Shared payment rules depend on normalized ports; adapters translate each provider's vocabulary at the edge. This prevents three divergent settlement/refund implementations. | `provider/core`, `PaymentSettlementService` |
| Dependency inversion / hexagonal architecture | Application rules depend on interfaces the application owns. Provider HTTP clients implement those ports; provider DTOs do not leak into the domain. | `PaymentProviderRegistry`, adapter packages |
| Provider differences | Paystack and Flutterwave V3 use their own REST schemas and webhook protocols. Stripe Checkout Sessions wrap a PaymentIntent lifecycle and use Stripe event/signature semantics. Normalize only the concepts all adapters actually share. | `provider/paystack`, `provider/flutterwave`, `provider/stripe` |
| Local/provider idempotency | The local key prevents duplicate work in this service; provider idempotency prevents duplicate POST effects at a provider when its API supports it. Both do different jobs. | payment/refund services and adapter request options |
| Safe routing | Save the chosen provider on an attempt before calling it. An ambiguous timeout stays with that provider; only a new attempt can choose another. | `ProviderRoutingPolicy`, `Payment.provider` |
| Webhook authentication | Preserve raw bytes and authenticate with that provider's protocol before trusting or storing the event. Then verify critical payment data against the provider. | webhook controllers and provider verifiers |
| Durable inbox / exactly-once effect | Delivery is at least once. A unique deduplication key plus locked state transition makes the financial effect happen once. Store before acknowledging; workers can recover after a crash. | `WebhookInboxService`, `WebhookInboxWorker`, V9 |
| Transactional outbox | Write the financial state and an outbox record in the same database transaction. A worker publishes afterward; a send failure cannot roll back a payment already committed. | `OutboxEventStore`, `OutboxWorker`, V10 |
| External-call boundaries | Reserve and commit local intent, call the provider outside a DB transaction, then lock and apply the result in a new transaction. This avoids holding row locks over uncertain network time. | payment/refund application services |
| Retry/backoff/circuit breaker | Retry transient safe reads with exponential randomized delay. Stop sending calls to an unhealthy provider while its circuit is open. Never blindly replay an ambiguous money-moving POST. | `ProviderCallExecutor`, `ProviderFailureClassifier` |
| Ambiguous network failure | A timeout only says we did not receive a response; it does not prove the provider did nothing. Preserve uncertainty and reconcile with the same provider. | provider error handling and reconciliation |
| Reconciliation | Query the provider saved on an attempt, compare reference/amount/currency/state, repair only a matching local lag, and record contradictions for review. | `PaymentReconciler`, `RefundReconciler`, V11 |
| Pessimistic locking / refund invariant | Lock the payment row before computing remaining refundable capacity. Concurrent refund requests serialize, so successful refunds cannot exceed the captured amount. | `PaymentRepository`, `Payment.reserveRefund` |
| `SKIP LOCKED` workers | A worker claims rows that are ready and unlocked, leaving rows held by other workers alone. Several instances can claim different inbox/outbox work without waiting on the same row. | inbox/outbox repositories |
| Ledger basics | Each journal has debits equal to credits; postings are immutable. State transitions and journals commit atomically, while account balance is derived by summing postings. | `LedgerPostingService`, V12 |
| Observability | Structured, low-cardinality counters/timers show provider health and database-backed gauges show durable backlog. Logs/traces add context but must avoid secrets and sensitive payment data. | `ProviderCallExecutor`, `PaymentQueueMetrics` |
| Security | Webhooks are publicly routable but require signatures. API operations require a caller identity, refunds require `ADMIN`, and raw PAN/CVV never enters this service. | `SecurityConfig`, `RefundController` |
| Docker and CI/CD | Docker builds a non-root Java runtime image. CI tests and builds/publishes an artifact; deployment and safe schema rollout are a separate controlled stage. | `Dockerfile`, `compose.yaml`, `.github/workflows/ci-cd.yml` |
| Concurrency and invariant tests | Use PostgreSQL/Testcontainers for locks and constraints, WireMock for provider failures, and assert invariants directly rather than relying only on happy-path status codes. | `*PostgresIntegrationTests`, adapter tests |
| Modular monolith | Keep modules separated by domain boundaries while they share one deployable process and database. Split only when ownership, scaling, or operational evidence justifies distributed-system costs. | package structure under `com.faith.pay` |

### Final interview prompts

1. **Why is a timeout not a failed payment?** The remote request may have succeeded even though its response was lost; retrying blindly can double-charge. Reconcile the persisted provider attempt.
2. **How do you make a webhook safe under duplicate delivery?** Authenticate, insert into a durable inbox with a provider-scoped unique deduplication key, then use locked idempotent domain transitions.
3. **How do you stop two refunds from exceeding a payment?** Lock the payment row, check remaining capacity, reserve atomically, and enforce database/domain invariants.
4. **What does the ledger add beyond `SUCCESS`?** It records balanced value movements as immutable history and allows balances to be derived and audited.
5. **Why not make each provider its own microservice now?** The modular monolith already isolates provider adapters while keeping settlement/ledger transactions atomic. A service split would introduce network and distributed-transaction failure modes without a demonstrated need.

### Current documentation checked for this checkpoint

Spring Boot documents Micrometer registry auto-configuration and Prometheus scraping at `/actuator/prometheus`; the exporter dependency and endpoint exposure are both required. [Spring Boot metrics reference](https://docs.spring.io/spring-boot/reference/actuator/metrics.html), [Prometheus endpoint requirements](https://docs.spring.io/spring-boot/4.2/reference/actuator/endpoints.html). The current local Compose pins use Prometheus 3.14.0 and Grafana 13.2.4, checked against their [official Prometheus releases](https://github.com/prometheus/prometheus/releases) and [Grafana releases](https://github.com/grafana/grafana/releases).
