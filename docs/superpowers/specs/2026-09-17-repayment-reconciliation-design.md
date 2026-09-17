# Repayment Reconciliation Service — Design

## Context

Next item on `docs/PROGRESS.md`. Per `docs/bridgepay-platform-spec.md` §4/§5/§8/§10,
this service tracks scheduled vs. received installments and integrates with
**Paddle** for shopper installment collection (weekly billing cycle; Paddle has
no native "N cycles then stop" concept, so that logic lives on our side). It
consumes `applications.approved` (published by Application Service, already
built) and publishes the 4 `repayments.*` topics that Notifications Service
already consumes (`RepaymentEvents`/`RepaymentEventConsumer`, built against its
own hand-rolled guess at the payload shape — this doc is the real contract
those must match, byte-for-byte, same as Mock Credit Bureau's shape had to
match Credit Risk Engine's).

Resolved before writing this doc:
- **Paddle integration mode**: real Paddle Sandbox (API key + webhook secret),
  not a self-contained mock like Mock Credit Bureau — confirmed against
  Paddle's actual developer docs (subscription creation, transaction items,
  webhook signature verification) rather than guessed from memory, the same
  standard this project already holds itself to for Spring Boot 4 migration
  claims (`.claude/rules/spring-boot-4-migration.md`).
- **Applicant Service gets two small internal endpoints** (`GET
  /internal/applicants/{id}`, a way to set `paddle_customer_id`) even though
  that service is already marked done — its `paddle_customer_id` column
  otherwise stays permanently dead, and this service needs the applicant's
  email/name to create a Paddle customer.
- **No frontend exists yet**, so a real Paddle checkout can't be completed
  automatically. Paddle has no server-to-server "just charge this card"
  endpoint for a brand-new subscription (PCI reasons — true for every Paddle
  integration, not a project-specific gap); a subscription is only created as
  a side effect of a transaction completing via checkout. This service creates
  the transaction and logs the returned `checkout.url` (same "log-only, no
  real delivery channel" simplification Notifications Service already uses
  for v1 email/SMS) — manual sandbox smoke testing means opening that URL and
  paying with a Paddle test card, same style as this project's other
  curl-driven manual smoke tests (no UI anywhere yet).
- **Credentials**: `paddle.api-key` / `paddle.webhook-secret` / `paddle.base-url`
  are env-var-backed properties (default base URL: Paddle's sandbox host),
  scaffolded now, real sandbox values dropped in later — same pattern as every
  other secret in this project (Kubernetes Secrets later, never baked in).

## Service shape

- `services/repayment-reconciliation-service/` — Java 21, Spring Boot 4.1.1,
  Maven, package `com.bridgepay.repayment`.
- Port `8084` (already reserved by Credit Risk Engine's
  `REPAYMENT_RECONCILIATION_URL` default). Own docker-compose Postgres, host
  port `5435` (5432/5433/5434 taken), Flyway-migrated `repayment` schema.
  Docker-compose also gets its own single-node KRaft Kafka broker, same
  `apache/kafka` image and shape as Notifications Service's compose file.
- Standard `SecurityConfig` (`@Profile("!local")`, JWT resource server) /
  `LocalDevSecurityConfig` (`@Profile("local")`, permitAll) pair.
- UUIDv7 PKs via `UuidCreator.getTimeOrderedEpoch()`, `@Version` optimistic
  locking on `repayment_plans`, `@EnableJpaAuditing`,
  `spring.threads.virtual.enabled=true`, global `@RestControllerAdvice` error
  shape, Resilience4j core (programmatic, not the annotation-based
  integration — no confirmed Boot 4-compatible module, per the migration
  notes) — same as every other service.

## Data model

```
repayment_plans
  id                    uuid (UUIDv7) PK
  application_id        uuid UNIQUE NOT NULL   -- reference only; also the redelivery-idempotency guard
  applicant_id          uuid NOT NULL          -- reference only
  paddle_customer_id    varchar NOT NULL
  paddle_subscription_id varchar               -- nullable until the first transaction.completed webhook adopts it
  total_amount          numeric NOT NULL
  installment_count     int NOT NULL
  installment_amount    numeric NOT NULL
  status                varchar NOT NULL       -- ACTIVE / COMPLETED / DEFAULTED / CANCELLED
                                                -- CANCELLED is reserved for a future manual ops action
                                                -- (e.g. a refund/goodwill cancel); nothing in this design
                                                -- sets it — only COMPLETED/DEFAULTED are webhook-driven
  version               int NOT NULL           -- optimistic locking, updated from the webhook path only (no scheduled path exists in this design, see Non-goals)
  created_at / updated_at

installments
  id                    uuid (UUIDv7) PK
  repayment_plan_id     uuid NOT NULL FK -> repayment_plans
  sequence_number       int NOT NULL
  due_date              date NOT NULL          -- our own weekly-cadence estimate from plan creation; informational only, Paddle's own billing schedule is authoritative for when it actually attempts to charge
  amount                numeric NOT NULL
  status                varchar NOT NULL       -- SCHEDULED / PAID / LATE / MISSED
  paddle_transaction_id varchar
  paid_at               timestamptz
  created_at / updated_at

outbox_events            -- identical shape to Application Service's
  id / event_id / aggregate_id / topic / partition_key / payload / published / created_at
```

Indexes: `installments(due_date, status)` (ops visibility query pattern, per
spec §9), `repayment_plans(application_id)` (already unique).

No cross-schema FK anywhere — `application_id`/`applicant_id` are plain UUID
columns, validated nowhere at the DB level (app-layer only, per CLAUDE.md).

## Applicant Service changes

Two additions to the already-built service, both internal-only (no gateway
route, same trust model as Credit Risk Engine's `/internal/score`):

```
GET   /internal/applicants/{id}
      -> { id, firstName, lastName, email, paddleCustomerId }

PATCH /internal/applicants/{id}/paddle-customer
      body: { paddleCustomerId }
      -> 204, sets applicants.paddle_customer_id
```

## Paddle integration

`PaddleClient` interface (mirrors `BureauClient`/`CreditRiskClient` shape),
`HttpPaddleClient` impl, one Resilience4j core circuit breaker wrapping every
outbound call:

1. **Find-or-create customer** — `GET /customers?email=...` first (avoid
   duplicates on redelivery/restart), else `POST /customers { email, name }`.
   Result cached via the Applicant Service PATCH above so this only happens
   once per applicant.
2. **Create transaction** — `POST /transactions`:
   ```json
   {
     "customer_id": "ctm_...",
     "collection_mode": "automatic",
     "items": [{
       "quantity": 1,
       "price": {
         "description": "BridgePay installment plan",
         "billing_cycle": { "interval": "week", "frequency": 1 },
         "tax_mode": "account_setting",
         "unit_price": { "amount": "<installmentAmount in minor units>", "currency_code": "USD" }
       }
     }]
   }
   ```
   No `address_id` — this platform never collects shopper addresses; Paddle's
   hosted checkout collects one itself if tax calculation needs it. Returns a
   transaction `id` (stored as a placeholder in `paddle_subscription_id` until
   the real subscription id is known) and a `checkout.url`, logged at INFO.
3. **Cancel subscription** — `POST /subscriptions/{id}/cancel { "effective_from": "immediately" }`,
   called once the final installment clears.

Paddle auto-creates the subscription only once this transaction completes
(confirmed against Paddle's docs, not assumed) — there is no separate
"create subscription" call.

### Webhook handling

`POST /webhooks/paddle` — deliberately **not** under `/internal/` (which in
this project means "ClusterIP-only, unreachable from outside the cluster,"
per spec §11) and **not** under `/api/v1/` (which means "JWT required through
the gateway"). This is the one endpoint in the whole platform that must be
reachable by a third party over the public internet with neither control:
the gateway must route it without requiring a JWT (Paddle can't present a
Keycloak token), and its own control is HMAC verification instead. Verified,
confirmed against Paddle's docs:

- Header `Paddle-Signature: ts=<unix>;h1=<hex>`.
- Expected = `HMAC-SHA256(webhookSecret, "<ts>:<rawBody>")`.
- Compare with `MessageDigest.isEqual` (timing-safe), reject (400, log, no
  processing) on mismatch. Raw body must be read untransformed — the
  signature is computed over the exact bytes Paddle sent, so this handler
  reads the raw request body before any JSON parsing.

Event handling, matched by `subscription_id` (falling back to `transaction.id`
for the very first event, before a plan has a subscription id yet):

| Event | Effect |
|---|---|
| `transaction.completed` | If `paddle_subscription_id` on the plan still holds the placeholder transaction id, adopt the payload's real `subscription_id`. Find the next `SCHEDULED`/`LATE` installment in sequence for that plan, mark `PAID`, record `paddle_transaction_id`/`paid_at`, publish `repayments.installment-paid`. If that was the last installment: mark plan `COMPLETED`, publish `repayments.plan-completed`, call `PaddleClient.cancelSubscription` (immediately). |
| `transaction.payment_failed`, `subscription.past_due` | Mark the current pending installment `LATE`, publish `repayments.installment-missed`. |
| `subscription.canceled` | If the plan is already `COMPLETED` (we just canceled it ourselves in the row above), no-op — this is just Paddle confirming our own call. If the plan is still `ACTIVE`, Paddle's dunning gave up on its own: mark plan `DEFAULTED`, mark the pending installment `MISSED`, publish `repayments.plan-defaulted`. This distinguishes "we asked Paddle to cancel" from "Paddle canceled on us" without needing an extra flag — plan status at the moment the webhook arrives already tells us which case we're in. |

Every branch is naturally idempotent against Paddle's at-least-once webhook
delivery: re-marking an already-`PAID` installment `PAID` again, or an
already-`DEFAULTED` plan `DEFAULTED` again, is a no-op update, same pattern as
`plan-completed`/`plan-defaulted` consumption being a no-op re-set per spec §10.

## Kafka consumption (inbound)

`ApplicationEventConsumer`, one `@KafkaListener` on `applications.approved`,
same envelope (`EventEnvelope<T>`) and payload shape
(`ApplicationEvents.Approved(applicantId, merchantId, amount,
installmentCount, installmentAmount)`) as Application Service's own copy —
`applicationId` comes from `envelope.aggregateId()`. Duplicated locally rather
than shared, matching this project's existing convention (Notifications
Service already duplicates these same records rather than depending on a
shared library, which doesn't exist here).

Reuses Notifications Service's exact `KafkaConsumerConfig`
(`DefaultErrorHandler`, 3 retries / 1s fixed backoff, then log ERROR and skip
— no dead-letter topic). If Paddle's circuit is open long enough that
retries exhaust, that application's repayment plan is silently dropped after
a loud log line — an accepted v1 tradeoff, consistent with the rest of this
project's "defer heavier infra" calls.

Idempotency: the unique constraint on `repayment_plans.application_id` makes
a redelivery a harmless failed insert (per spec §10) — caught, logged DEBUG,
skipped, no Paddle calls repeated.

## Kafka publishing (outbound)

Own `outbox_events` table + `OutboxPublisher` scheduled poller, copied from
Application Service's (`@Scheduled(fixedDelay = 2000)`, same
find-unpublished/send/mark-published shape). Publishes:

| Topic | Partition key | Payload |
|---|---|---|
| `repayments.installment-paid` | `repaymentPlanId` | `InstallmentPaid(applicantId, installmentId, sequenceNumber, amount)` |
| `repayments.installment-missed` | `repaymentPlanId` | `InstallmentMissed(applicantId, installmentId, sequenceNumber, dueDate)` |
| `repayments.plan-completed` | `repaymentPlanId` | `PlanCompleted(applicantId, applicationId)` |
| `repayments.plan-defaulted` | `repaymentPlanId` | `PlanDefaulted(applicantId, applicationId)` |

These match Notifications Service's `RepaymentEvents` record shapes exactly,
including `applicantId` on all four (that service's own stated assumption,
confirmed correct here).

## Internal endpoint

`GET /internal/repayment-history/{applicantId}` →
`{ completedPlans, defaultedPlans, latePaymentCount, onTimeRate }` — a
read-only aggregate query over this applicant's `repayment_plans`/
`installments`, `permitAll` under the real `SecurityConfig` (network
isolation is the control, same as Credit Risk Engine's `/internal/score` —
moot until cross-service JWT propagation is designed, per PROGRESS.md's open
risk). Consumed synchronously by Credit Risk Engine's existing
`RepaymentHistoryClient`/`HttpRepaymentHistoryClient`, which this finally
makes real instead of always circuit-breaker-falling-back.

## Testing

- Testcontainers Postgres + Kafka, matching every other service — real infra,
  not mocks.
- `PaddleClient` is an interface specifically so its HTTP integration test can
  run against a WireMock stub instead of Paddle's real sandbox (which can't
  run in a container) — covers find-or-create-customer, transaction creation,
  and cancel-subscription request/response shapes.
- One webhook test drives a fabricated `transaction.completed` payload with a
  known test secret through the real `HMAC-SHA256`/`MessageDigest.isEqual`
  verifier end-to-end, plus a tampered-body/wrong-signature case that must be
  rejected — proving the security-critical path actually works, not just
  compiles.
- Kafka consumer test: publish a real `applications.approved` message,
  assert `repayment_plans`/`installments` rows land correctly and that
  redelivering the same `applicationId` doesn't duplicate them.

## Non-goals for v1

- No automated "sweep overdue installments" scheduled job — webhooks are the
  sole source of truth for status transitions; `installments(due_date,
  status)` exists for ops query visibility, not a polling safety net. Worth
  adding later if webhook delivery proves unreliable in practice.
- No real checkout UI — logging `checkout.url` is the v1 "delivery channel"
  for it, same simplification as Notifications Service's log-only
  email/SMS. Superseded once the Angular frontend exists.
- No dead-letter topic, no schema registry/Avro — consistent with every
  other service.
