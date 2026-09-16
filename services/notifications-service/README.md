# BridgePay — Notifications Service

Pure Kafka consumer: no outbox, no HTTP API beyond actuator. Consumes all 7
topics from spec section 10 (`applications.approved`/`manual-review`/`declined`,
`repayments.installment-paid`/`installment-missed`/`plan-completed`/`plan-defaulted`),
persists one idempotency-guarding row per event to `notification_log`, and (v1)
logs what it would have sent instead of delivering real email/SMS.

See `docs/superpowers/specs/2026-09-16-notifications-service-design.md` for
the full design.

## Run locally

```bash
docker-compose up --build
```

Uses the `local` Spring profile (JWT auth disabled) plus its own Postgres and
its own single-node KRaft Kafka broker (this is the first service in the
project to run a real local broker). No other services need to be running —
Repayment Reconciliation Service doesn't exist yet, so the 4 `repayments.*`
topics simply never receive real traffic until it does.

## Run tests

```bash
mvn clean verify
```

Two Testcontainers-backed integration test classes (`ApplicationEventConsumerIntegrationTest`,
`RepaymentEventConsumerIntegrationTest`) publish real messages matching each
topic's envelope/payload contract against a real Kafka broker + Postgres, and
assert both the `notification_log` row and that redelivering the same
`eventId` doesn't insert a second row. `NotificationServiceTest` covers the
same idempotency logic directly against the service.

## Design notes / deviations from the written spec

- **No `GlobalExceptionHandler`/`@RestControllerAdvice`**: the spec's design
  doc calls for keeping this convention "same as every other service," but
  this service has zero `@RestController`s to advise — there's nothing for
  it to do. Skipped as genuinely dead code; add it if a real endpoint ever
  lands here.
- **No `ErrorHandlingDeserializer`**: the design doc's Kafka Consumption
  section mentions wrapping deserialization to survive a malformed message.
  In practice the Kafka value deserializer here is a plain `StringDeserializer`
  (matching Application Service's producer, which sends a hand-serialized
  JSON string, not Spring Kafka's own `JsonSerializer`) — a `String`
  deserializer essentially cannot throw. The actual envelope/payload JSON
  parsing happens inside each listener via `ObjectMapper`, so a malformed
  message surfaces as a normal listener exception, already covered by the
  `DefaultErrorHandler` retry-then-skip policy. Same resilience property,
  one fewer moving part.
- **Container lifecycle in tests**: the spec's own `TestcontainersConfiguration.java`
  precedent (Application Service, currently unused there) uses Spring Boot's
  `@ServiceConnection` beans rather than plain `@Container` fields. This
  service actually needed that pattern — three test classes share one
  Postgres + one Kafka container via `AbstractKafkaIntegrationTest`, and a
  plain `@Container` static field gets stopped by the JUnit Testcontainers
  extension after each test class, which breaks the *next* class's reused,
  cached Spring context. `@ServiceConnection` ties container lifecycle to the
  Spring context instead, which is what Spring's test context caching
  actually needs.
- **`NotificationService.recordAndSend` is not `@Transactional`**: `saveAndFlush`
  already runs its own transactional boundary (Spring Data JPA repositories
  are `@Transactional` out of the box). Adding a second, outer `@Transactional`
  here causes the constraint-violation path to throw `UnexpectedRollbackException`
  on return, because the shared transaction gets marked rollback-only by the
  *inner* repository call before this method's own `catch` block ever runs.
  Caught this via the test suite, not by inspection.
- **`RepaymentEvents` payload shapes are this service's own invention**:
  Repayment Reconciliation Service doesn't exist yet, so there's no real
  producer to mirror (unlike `ApplicationEvents`, which is copied field-for-field
  from `application-service`'s actual producer code). Includes `applicantId`
  on all 4 repayment payloads even though the platform spec's "payload
  highlights" column doesn't list it — `notification_log` requires it, and
  `repayment_plans` (spec section 8) already carries `applicant_id`, so it's
  a reasonable inference, not a guess. Whoever builds Repayment Reconciliation
  Service must actually emit it.

## Smoke-tested manually

`docker-compose up --build`, then published a real message via
`kafka-console-producer.sh` to `applications.declined` and confirmed (via
`psql` and the container logs) that the row landed in `notification_log` and
the "would send" line was logged. Kafka's own `kafka-consumer-groups.sh --describe`
showed some transient rebalance churn during this manual test with 7
independent single-partition consumers in one group — messages were still
correctly and durably processed once the group settled (confirmed by
`LOG-END-OFFSET` matching `CURRENT-OFFSET` and the DB row landing), so this
looks like Kafka's normal group-coordinator startup behavior under this
setup rather than a functional defect, but flagging it since it wasn't
instant the way a single-consumer group usually is.
