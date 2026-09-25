# Failed Events Recovery — Design

Fourth and last of the "expansion" sub-projects (shopper → merchant → risk/ML
→ **platform/ops**).

## Problem

Repayment Reconciliation's `applications.approved` consumer turns an approval
into a Paddle repayment plan. Its `DefaultErrorHandler` retries 3× / 1s, then
logs and commits the offset — the event is gone. A Paddle outage longer than
~4 seconds means an approved shopper silently never gets a repayment plan;
the only trace is a log line. Ops has no way to see or recover it.

Found while designing: Repayment Reconciliation's `LocalDevSecurityConfig`
(used by every docker-compose run) has no `@EnableMethodSecurity` and its
stub principal carries no authorities, so any `@PreAuthorize` added to this
service would be silently unenforced under `local`. Application Service's
`LocalDevSecurityConfig` already solved this.

## Solution

A database-backed dead-letter store inside Repayment Reconciliation, an
ops-only API to list and retry entries, and a `main-app` page on top of it.
No new services, topics, or infrastructure.

### Scope

- **In:** Repayment Reconciliation's `applications.approved` consumer.
- **Out:** Notifications Service stays log-and-drop (a lost notification is
  one missed log line in v1, not lost money work). Outbox backlog counts,
  request tracing / JSON logs, Kafka `.DLT` topics — all deferred.

### Data — `V2__create_failed_events.sql` (schema `repayment`)

`failed_events`:

| column | type | notes |
|---|---|---|
| `id` | uuid PK | UUIDv7 |
| `topic` | varchar not null | e.g. `applications.approved` |
| `message_key` | varchar null | Kafka record key (application id for approved events) |
| `payload` | text not null | raw record value, replayed verbatim |
| `error_message` | text not null | latest failure's message (root cause) |
| `status` | varchar not null | `FAILED` \| `RESOLVED` |
| `attempts` | int not null | 1 on insert (the exhausted delivery), +1 per failed manual retry |
| `version` | bigint not null default 0 | `@Version` — concurrent retry clicks |
| `created_at`, `updated_at` | timestamptz not null default now() | JPA auditing |

Index on `(status, created_at desc)`.

### Capture — `KafkaConsumerConfig`

Keep `FixedBackOff(1000L, 3)`. The recoverer, after logging as today, saves a
`FAILED` row from the `ConsumerRecord` (topic, key, value, exception root-cause
message). The offset still commits, so the partition never blocks. If saving
the row itself throws, log it at ERROR and continue — no worse than today.

### Service — `FailedEventService`

- `Page<FailedEvent> list(String status, Pageable)` — `ALL` or a
  `FailedEventStatus` name; newest first; unknown status → `IllegalArgumentException`
  → `400 VALIDATION_ERROR` (add the handler to this service's
  `GlobalExceptionHandler` if absent).
- `FailedEvent retry(UUID id)`:
  - not found → `404`.
  - `RESOLVED` → `409` ("already resolved").
  - topic ≠ `applications.approved` → `409` ("no retry handler for topic") —
    guards a future listener from being replayed through the wrong handler.
  - otherwise call `ApplicationEventConsumer.onApproved(payload)` directly
    (in-process; the handler is already idempotent via
    `findByApplicationId`). Success → `RESOLVED`. Exception → stays `FAILED`,
    `attempts + 1`, `error_message` updated; the endpoint still returns `200`
    with the updated row (the retry request itself succeeded; the row shows
    the outcome).
  - Stale `@Version` → `409`.

### API — `FailedEventController`

Both `@PreAuthorize("hasRole('OPS')")`, same as `CreditApplicationController`.

- `GET /api/v1/ops/failed-events?status=FAILED|RESOLVED|ALL&page&size` —
  default `FAILED`. Page of `{id, topic, messageKey, errorMessage, status,
  attempts, createdAt, updatedAt}`. Payload is not returned (can be large; not
  useful in the UI).
- `POST /api/v1/ops/failed-events/{id}/retry` → the updated row.

### Security — `LocalDevSecurityConfig` parity

Bring Repayment Reconciliation's `local` config to parity with Application
Service's: `@EnableMethodSecurity`, and the stub filter copies every claim
from a present bearer token and maps `realm_access.roles` to `ROLE_<UPPER>`
authorities (same mapping as this service's real `SecurityConfig`). No token →
fixed demo subject, no authorities (unchanged). Existing
`/api/v1/repayment-plans` owner-check behavior must be unaffected.

### Gateway

New route `ops`: `Path=/api/v1/ops/**` → Repayment Reconciliation (same
base-url env var as the existing `repayment-plans` route).

### `main-app`

- New `FailedEvents` component at `/ops/failed-events` (`opsGuard`); sidebar
  link beside the review queue.
- Failed / Resolved / All filter strip (default Failed), table: failed-at,
  topic, application id (`messageKey`), error, attempts, status, **Retry**
  button (only on `FAILED` rows).
- Retry disables that row's button while in flight, then replaces the row
  with the response. A failed HTTP call shows a distinct error message.
- Load failure shows a distinct error, never the empty-state text (same
  pattern as `ReviewQueue`). Prev/Next pagination like `SalesPage`.

## Testing

- **Integration (real Testcontainers Postgres + Kafka), extending the
  existing `ApplicationEventConsumerIntegrationTest` pattern** with a
  `FakePaddleClient` switchable between failing and succeeding:
  - publish an approved event while Paddle fails → a `FAILED` row appears,
    no plan exists;
  - make Paddle succeed, `POST …/retry` → row `RESOLVED`, plan exists;
  - retry again → `409`;
  - retry while still failing → `200`, row `FAILED`, `attempts` = 2, error updated.
- **Security:** non-ops token → `403`, ops token → `200`, under the real
  `SecurityConfig` and under the `local` profile (the latter fails before the
  parity fix — that's the regression guard). `repayment-plans` owner tests
  still pass.
- **Unit:** `FailedEventService` status parsing and the 404/409 branches.
- **Frontend:** list renders, filter refetches, retry updates the row,
  in-flight disable, load error vs empty, retry error.
- **Manual:** full compose stack with the placeholder Paddle key (always
  `403`): approve an application as `ops1` → row appears on
  `/ops/failed-events` → retry keeps it `FAILED` with `attempts` 2. A real
  `RESOLVED` needs a real Paddle key; that path is covered by the integration
  test.

## Out of scope

Notifications Service dead-lettering, Kafka `.DLT` topics, outbox backlog
metrics, correlation-ID propagation / JSON logs, bulk retry, payload viewer,
automatic scheduled retries.
