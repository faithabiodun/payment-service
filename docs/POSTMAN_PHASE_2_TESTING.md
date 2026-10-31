# Postman guide — Payment Service Phase 2

## Import and configure

Import these two files into Postman:

* test-orders.postman_collection.json
* payment-service.postman_environment.local.json

The local environment is ignored by Git and has the provider webhook secrets copied from the app's .env without printing them. It is still missing API customer/admin/monitoring credentials because those are not present in .env. Add those to .env, restart the app, and enter the same account values into the local Postman environment. The committed payment-service.postman_environment.example.json remains a blank template. If you export another local copy, name it with the suffix .postman_environment.local.json; Git ignores that pattern.

The API user variables are separate from payment-provider API keys:

| Postman variable | App environment variable | Role |
|---|---|---|
| apiUsername / apiPassword | API_CUSTOMER_USERNAME / API_CUSTOMER_PASSWORD | CUSTOMER for test orders, payments and status |
| apiAdminUsername / apiAdminPassword | API_ADMIN_USERNAME / API_ADMIN_PASSWORD | ADMIN for refunds |
| apiMonitoringUsername / apiMonitoringPassword | API_MONITORING_USERNAME / API_MONITORING_PASSWORD | MONITORING for Prometheus metrics |
| paystackSecretKey | PAYSTACK_SECRET_KEY | Signs the Paystack inbox-only sample |
| flutterwaveWebhookSecretHash | FLUTTERWAVE_V3_WEBHOOK_SECRET_HASH | Flutterwave V3 verif-hash sample |
| stripeWebhookSigningSecret | STRIPE_WEBHOOK_SECRET | Stripe whsec_ signing secret; not STRIPE_SECRET_KEY |

The collection uses customer Basic authentication by default, switches to the admin account for refunds, monitoring credentials for metrics, and no Basic authentication for the browser callback or webhooks. The webhook controllers verify their provider-specific signatures.

## Live payment flow

Use a separate order for each provider. The order total is authoritative; payment initialization only sends orderId and a provider choice.

### Create a test order

~~~http
POST {{baseUrl}}/api/v1/test-orders
Content-Type: application/json

{
  "email": "faith@example.com",
  "amountKobo": 500000
}
~~~

The example environment defaults to NGN 5,000 (500000 minor units) and faith@example.com. Edit orderAmountKobo and orderEmail there to change the sample. The collection saves id as orderId and generates a fresh paymentIdempotencyKey for this order.

### Initialize one provider

Run one of the collection requests below after creating an order. The payment response should return a payment reference, amount and hosted checkout URL.

~~~json
{
  "orderId": {{orderId}},
  "provider": "PAYSTACK"
}
~~~

Use FLUTTERWAVE or STRIPE in the provider-specific request instead. Open authorizationUrl and finish a real provider test-mode checkout. The collection’s replay request reuses the same idempotency key and should return the same payment attempt.

Then run:

~~~http
POST {{baseUrl}}/api/v1/payments/{{reference}}/verify
~~~

The callback request is only a browser-return message. Verification queries the provider saved to that attempt; the redirect does not mark a payment successful.

### Refund only a real successful payment

The existing refund request is admin-authenticated and sends:

~~~json
{
  "amountKobo": 125000,
  "reason": "sample partial refund"
}
~~~

The environment defaults to NGN 1,250 (125000 kobo). Run the request only after the provider transaction is genuinely successful and local state is SUCCESS or PARTIALLY_REFUNDED. Immediately run Replay Last Refund to send the same idempotency key. For the default 500000-kobo payment and a successful 125000-kobo refund, the default 400000-kobo over-capacity sample exceeds the 375000 remaining amount and should return a 4xx response without starting another provider refund.

## Signed webhook inbox samples

The collection’s valid webhook requests intentionally use signed, unknown/no-op event names. They test provider-specific signature verification, durable inbox acknowledgement, deduplication and metrics without claiming that a transaction occurred. Run a valid event twice, or use Postman Runner with 100 iterations, to exercise duplicate delivery. Change/clear webhookSampleId in the local environment when starting a fresh deduplication exercise.

The request scripts build and sign these representative bodies at send time:

**Paystack** (HMAC-SHA512 with the configured Paystack secret):

~~~json
{
  "event": "postman.inbox.test",
  "data": { "id": "<stable-sample-id>", "status": "received" }
}
~~~

**Flutterwave V3** (verif-hash equals the configured dashboard secret hash):

~~~json
{
  "event": "postman.inbox.test",
  "data": { "id": "<stable-sample-id>", "status": "received" }
}
~~~

**Stripe** (raw body signed with a current timestamp and the endpoint whsec_ secret):

~~~json
{
  "id": "evt_postman_<stable-sample-id>",
  "object": "event",
  "api_version": "2026-09-30.endive",
  "created": 1793455200,
  "livemode": false,
  "type": "customer.created",
  "data": { "object": { "id": "cus_postman_sample", "object": "customer" } }
}
~~~

The Stripe request script generates the timestamp at send time. customer.created is deliberately outside the payment/refund allow-list, so it is authenticated, stored, deduplicated and then ignored by financial logic. The invalid-signature requests should return 401 for Paystack/Flutterwave and 400 for Stripe.

**Do not edit these no-op events into fake charge.success, charge.completed, or checkout.session.completed events to simulate a paid order.** A real settlement requires a real provider transaction whose provider API verification matches the local reference, amount and currency. For live provider webhooks, complete the test checkout and configure the provider dashboard/test CLI to send its actual signed event to your reachable callback URL.

## What Postman can and cannot exercise

Postman can exercise HTTP authentication/authorization, payment initialization, provider selection, callback behavior, provider-backed verification, refunds, signature rejection, inbox acknowledgement/deduplication and the monitoring endpoint.

The modular monolith does not expose public APIs for manually claiming inbox/outbox jobs, inspecting journals, or triggering reconciliation. Use the database queries below for read-only inspection and the existing tests for internal concurrency/failure behavior. The current outbox sink logs event summaries; it does not send email.

### Inbox and outbox state (read-only SQL)

~~~sql
SELECT provider, event_type, status, attempt_count, received_at, processed_at
FROM webhook_inbox
ORDER BY received_at DESC
LIMIT 30;

SELECT event_type, status, attempt_count, created_at, published_at
FROM outbox_events
ORDER BY created_at DESC
LIMIT 30;
~~~

Do not select raw_payload for routine checks; it can contain customer/provider data.

### Ledger balance invariant (read-only SQL)

This query should return no rows:

~~~sql
SELECT je.event_key, je.currency,
       SUM(lp.debit_minor) AS debit_minor,
       SUM(lp.credit_minor) AS credit_minor
FROM journal_entries je
JOIN ledger_postings lp ON lp.journal_entry_id = je.id
GROUP BY je.id, je.event_key, je.currency
HAVING SUM(lp.debit_minor) <> SUM(lp.credit_minor);
~~~

To derive an account balance in debit-normal terms:

~~~sql
SELECT a.account_code, p.currency,
       SUM(p.debit_minor - p.credit_minor) AS net_debit_minor
FROM ledger_accounts a
JOIN ledger_postings p ON p.account_id = a.id
GROUP BY a.account_code, p.currency
ORDER BY a.account_code, p.currency;
~~~

The database also rejects unbalanced journal commits and ledger updates/deletes.

### Reconciliation history (read-only SQL)

~~~sql
SELECT target_type, target_reference, source, outcome, discrepancy_type, created_at
FROM reconciliation_attempts
ORDER BY created_at DESC
LIMIT 50;
~~~

## Automated Phase 2 coverage

Postman does not replace PostgreSQL/Testcontainers, WireMock or concurrency tests. Run mvn test with Docker available for the whole suite. Focused test classes include:

* Provider mapping and routing: PaystackPaymentProviderMappingTests, FlutterwaveV3PaymentProviderMappingTests, StripeStateMappingTests, ProviderRoutingPolicyTests.
* Resilience/provider failures: ProviderResilienceTests, PaystackClientWireMockIntegrationTests, FlutterwaveV3ProviderWireMockTests, StripePaymentProviderWireMockTests.
* Webhooks/inbox: FlutterwaveWebhookVerifierTests, StripeWebhookServiceTests, WebhookInboxTests, WebhookInboxPostgresIntegrationTests.
* Refund, ledger, outbox and reconciliation: RefundInvariantIntegrationTests, LedgerPostgresIntegrationTests, OutboxPostgresIntegrationTests, PaymentReconciliationIntegrationTests, PaymentRefundEndToEndTests.

PostgreSQL tests require a working Docker daemon. They cover row locking, constraints, inbox/outbox persistence, ledger immutability and concurrent financial invariants that Postman cannot reliably prove.
