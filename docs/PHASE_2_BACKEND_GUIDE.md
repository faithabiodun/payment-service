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
- Refund create and payment create currently translate Stripe SDK exceptions to provider-unavailable errors conservatively. Checkpoint 9 will classify permanent 4xx/auth errors separately from ambiguous connection failures.
- Durable inbox storage is not implemented yet; the webhook path is synchronous. Checkpoint 7 adds durable acknowledgement and worker recovery.
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

**Exercise:** Why does the local payment store both `cs_...` and `pi_...` identifiers?

**Solution:** The Checkout Session ID identifies the hosted checkout used to retrieve the attempt before payment completes. The PaymentIntent ID identifies Stripe's payment lifecycle and is the correct reference for settlement/refunds. They describe related but different objects.

### What you should be able to explain as a backend developer

Stripe Checkout is the customer-facing hosted page; the PaymentIntent is the underlying financial lifecycle. The adapter maps Stripe's richer states into our neutral payment states. The raw webhook is authenticated with Stripe's SDK and current provider data is re-queried before a shared, locked settlement. The local idempotency key protects our API, while sending that key to Stripe protects Stripe's POST operation from duplicate creation.
