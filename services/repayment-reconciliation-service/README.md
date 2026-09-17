# BridgePay — Repayment Reconciliation Service

Tracks scheduled vs. received BNPL installments and integrates with **real
Paddle Sandbox** (not a mock) for shopper installment collection. Consumes
`applications.approved`, creates a Paddle customer + a weekly-recurring
non-catalog-price transaction, and reacts to Paddle's real webhooks
(`transaction.completed`, `transaction.payment_failed`/`subscription.past_due`,
`subscription.canceled`) to drive installment/plan status and publish the 4
`repayments.*` topics Notifications Service already consumes.

See `docs/superpowers/specs/2026-09-17-repayment-reconciliation-design.md`
for the full design, including the Paddle API mechanics (confirmed against
Paddle's own developer docs, not guessed) and why a real checkout can't be
completed automatically until the Angular frontend exists.

## Run locally

```bash
docker-compose up --build
```

Uses the `local` Spring profile (JWT auth disabled) plus its own Postgres and
its own single-node KRaft Kafka broker. `PADDLE_API_KEY`/`PADDLE_WEBHOOK_SECRET`
default to `changeme` — fine for local smoke testing of the webhook signature
path (see below), but a real Paddle sandbox key is needed for
`findOrCreateCustomer`/`createInstallmentTransaction`/`cancelSubscription` to
actually reach Paddle rather than fail over via the circuit breaker. Applicant
Service must also be running (`APPLICANT_SERVICE_URL`, default
`http://host.docker.internal:8080`) for a real `applications.approved`
message to resolve to an applicant profile.

## Run tests

```bash
mvn clean verify
```

23/23 green. Breakdown:
- `PaddleSignatureVerifierTest` / `PaddleWebhookControllerTest`: drives real
  HMAC-SHA256-signed (and tampered/wrong-secret) payloads through the actual
  verifier end-to-end.
- `HttpPaddleClientWireMockTest`: stubs Paddle's real HTTP API shape (customer
  lookup/create, non-catalog transaction creation, subscription cancel) with
  WireMock, since a real sandbox can't run in a container. Required switching
  `HttpPaddleClient`'s underlying `RestClient` to force HTTP/1.1 — the JDK
  `HttpClient`'s default h2c upgrade negotiation doesn't complete cleanly
  against WireMock's Jetty engine on POST requests (EOF/RST_STREAM). HTTP/1.1
  is all Paddle's API needs, so this is a safe simplification, not a
  test-only workaround.
- `RepaymentPlanServiceTest` / `PaddleWebhookServiceTest` / `RepaymentHistoryServiceTest`:
  unit tests (Mockito) covering customer reuse-vs-create, redelivery
  idempotency, the full webhook state machine (paid/late/completed/defaulted,
  including the "did we cancel it or did Paddle" branch), and the
  repayment-history aggregation.
- `ApplicationEventConsumerIntegrationTest`: real Testcontainers Postgres +
  Kafka, `ApplicantClient`/`PaddleClient` swapped for in-memory fakes (`@Primary`
  test beans) since a real Applicant Service and Paddle sandbox can't run
  here — publishes a real `applications.approved` message and asserts the
  plan/installments land, and that redelivery doesn't duplicate them.

`OutboxPublisher` itself has no dedicated test, matching Application
Service's own precedent (same copied class, same gap there).

## Design notes / deviations from the written spec

- **Applicant Service gained two internal endpoints** (`GET
  /internal/applicants/{id}`, `PATCH /internal/applicants/{id}/paddle-customer`)
  as part of this work — see that service's own `InternalApplicantController`.
  Its `paddle_customer_id` column was dead until now.
- **Webhook route is `/webhooks/paddle`, not `/internal/webhooks/paddle`**:
  `/internal/**` means ClusterIP-only in this project; Paddle must reach this
  endpoint from the public internet, so it can't live under that prefix. HMAC
  signature verification is its access control instead of network isolation
  or a JWT.
- **`repayment_plans.paddle_subscription_id` doubles as a placeholder**: it
  holds the initial transaction id until the first `transaction.completed`
  webhook adopts the real subscription id Paddle creates as a side effect of
  that transaction completing (confirmed against Paddle's docs: there is no
  direct "create subscription" call).
- **No scheduled "sweep overdue installments" job**: webhooks are the sole
  source of truth for status transitions in this design;
  `installments(due_date, status)` exists for ops query visibility only.

## Smoke-tested manually

`docker-compose up --build`, then:
- `GET /actuator/health` → `UP`.
- `GET /internal/repayment-history/<random-uuid>` → all-zero response (no
  404), confirming the "brand-new applicant isn't an error" design choice.
- A real `POST /webhooks/paddle` with an HMAC-SHA256 signature computed by
  hand (`openssl dgst -sha256 -hmac`) against the `local` profile's default
  webhook secret → `200`, and the log shows it was actually parsed and routed
  (`No repayment plan found for completed transaction txn_smoke`, expected
  since no real plan exists for that fabricated id). The same payload with a
  tampered signature → `400`, never reaching the handler.
- Confirmed `Started RepaymentReconciliationServiceApplication` and the
  Flyway migration applying cleanly against a fresh Postgres.
