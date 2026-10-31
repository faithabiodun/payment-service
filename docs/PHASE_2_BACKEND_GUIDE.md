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
