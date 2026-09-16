# Notifications Service — Design

## Context

Next item on `docs/PROGRESS.md`. Per `docs/bridgepay-platform-spec.md` §4/§8/§10, this
is a pure Kafka *consumer* service: no outbox, no HTTP API beyond actuator. It
consumes 7 topics published by Application Service (already built, outbox
untested against a live broker) and the not-yet-built Repayment Reconciliation
Service, persists an idempotency-guarding log row per event, and (for v1)
logs what it would have sent instead of actually delivering email/SMS.

Two things the spec leaves open, resolved by discussion before this doc was written:
- **Notification channel**: log + persist only, no real email/SMS provider — matches this project's existing pattern of mocking external dependencies (Mock Credit Bureau) rather than integrating real ones for v1.
- **Topic scope**: build all 7 consumers now, against the spec's documented payload shapes, tested via Testcontainers Kafka with hand-published messages — not gated on Repayment Reconciliation Service existing.

This task also stands up the **first real local Kafka broker** anywhere in the
project (docker-compose, KRaft mode) — no service before this has run one.
Its integration tests are the first real proof that Application Service's
outbox poller can publish to a live broker successfully. It does **not**
close PROGRESS.md's separate "wire Kafka for real across services" item —
that also needs Repayment Reconciliation's publish side, which doesn't exist
yet.

## Service shape

- `services/notifications-service/` — Java 21, Spring Boot 4.1.1, Maven, package `com.bridgepay.notifications`.
- Port `8085` (8080/81/82/83 already taken by applicant/application/credit-risk/mock-bureau; 8084 reserved for Repayment Reconciliation via `credit-risk-engine`'s `REPAYMENT_RECONCILIATION_URL` default).
- Own docker-compose Postgres, host port 5434 (next free — 5432/5433 taken), Flyway-migrated `notifications` schema, one table.
- Ships the standard `SecurityConfig`(`@Profile("!local")`)/`LocalDevSecurityConfig`(`@Profile("local")`) pair per CLAUDE.md convention even though the only HTTP surface is actuator — keeps parity with every other service, guards against a future endpoint landing without security wired.
- UUIDv7 PKs, `@EnableJpaAuditing`, `spring.threads.virtual.enabled=true`, global `@RestControllerAdvice` error shape — same as every other service.

## Data model

```
notification_log
  id            uuid (UUIDv7) PK
  event_id      uuid UNIQUE NOT NULL   -- from the Kafka envelope, the idempotency guard
  applicant_id  uuid NOT NULL
  type          varchar NOT NULL       -- NotificationType enum name
  sent_at       timestamptz NOT NULL
```

One Flyway migration, `notifications` schema. No cross-schema FK — `applicant_id` is a plain UUID column, validated nowhere (this service never calls another service).

## Kafka consumption

`spring-kafka`. Consumer group `notifications-service`.

**Envelope** (matches spec §10 exactly):
```java
record EventEnvelope<T>(UUID eventId, String eventType, Instant occurredAt,
                         String aggregateId, int schemaVersion, T payload) {}
```
Dispatch is by **topic**, not by parsing `eventType` — the spec's own example (`"application.approved"`, singular) doesn't match its topic name (`applications.approved`, plural), so nothing here depends on that string matching. `eventType` is still logged for observability.

**Two consumer components**, one `@KafkaListener` method per topic:

| Component | Topic | Payload record | Notes |
|---|---|---|---|
| `ApplicationEventConsumer` | `applications.approved` | `ApplicationApprovedPayload(applicantId, merchantId, amount, installmentCount, installmentAmount)` | |
| | `applications.manual-review` | `ApplicationManualReviewPayload(applicantId, riskScore)` | |
| | `applications.declined` | `ApplicationDeclinedPayload(applicantId, riskScore)` | |
| `RepaymentEventConsumer` | `repayments.installment-paid` | `InstallmentPaidPayload(applicantId, installmentId, sequenceNumber, amount)` | |
| | `repayments.installment-missed` | `InstallmentMissedPayload(applicantId, installmentId, sequenceNumber, dueDate)` | |
| | `repayments.plan-completed` | `PlanCompletedPayload(applicantId, applicationId)` | |
| | `repayments.plan-defaulted` | `PlanDefaultedPayload(applicantId, applicationId)` | |

**Assumption, stated explicitly**: `notification_log` requires `applicant_id`, but the spec's "payload highlights" column for the 4 `repayments.*` topics doesn't list it (only `installmentId`/`applicationId`). Inferring it's included in the real payload anyway, since `repayment_plans` (spec §8) already carries `applicant_id` — the highlights column says "highlights," not "exhaustive fields." Whoever builds Repayment Reconciliation Service must include `applicantId` in these 4 event payloads for this to work; flagging it there too when that task starts.

`ErrorHandlingDeserializer` wraps JSON deserialization so one malformed message can't kill the consumer thread — falls into the same retry/skip handling as a processing error (below).

## Idempotency + "sending"

One shared method, reused by all 7 listeners:
```java
NotificationService.recordAndSend(UUID eventId, UUID applicantId, NotificationType type, String message)
```
- Attempt the insert. A unique-constraint violation on `event_id` **is** the idempotency check (not a separate exists-check-then-insert race) — catch `DataIntegrityViolationException`, log DEBUG "duplicate delivery, skipping", return.
- On successful insert: log INFO `"would send: <type> to applicant <id>: <message>"`. That log line is the entire v1 notification.

`NotificationType` enum: `APPLICATION_APPROVED`, `APPLICATION_MANUAL_REVIEW`, `APPLICATION_DECLINED`, `INSTALLMENT_PAID`, `INSTALLMENT_MISSED`, `PLAN_COMPLETED`, `PLAN_DEFAULTED`.

## Error handling

Spring Kafka `DefaultErrorHandler`, fixed backoff (3 retries / 1s), then log ERROR and skip (offset still commits — no reprocessing loop). No dead-letter topic: consistent with the spec's other "defer heavier infra" calls (no schema registry, no Avro). A notification lost after retries exhausted is an acceptable v1 tradeoff, loudly logged.

## Local dev / docker-compose

First docker-compose Kafka broker in the project: single-node KRaft mode via the official `apache/kafka` image (no ZooKeeper container). `notifications-service`'s own `docker-compose.yml` gets `postgres` + `kafka` + the app, `local` profile, same shape as sibling services' compose files.

## Testing

- Testcontainers Kafka + Postgres integration test per consumer: publish a real message matching each topic's envelope/payload contract, assert the `notification_log` row lands with correct `type`/`applicant_id`, and assert redelivering the same `eventId` doesn't insert a second row.
- Unit test for `NotificationService.recordAndSend` covering the insert and the duplicate-skip path in isolation (no Kafka needed).

## Non-goals for v1

- No real email/SMS delivery.
- No REST API for browsing notification history — spec doesn't call for one; add later if ops needs it.
- No dead-letter topic, no schema registry/Avro.
- Doesn't complete "wire Kafka for real across services" on its own (needs Repayment Reconciliation's publish side too) — but is the first real broker + the first real test of Application Service's outbox poller against it.
