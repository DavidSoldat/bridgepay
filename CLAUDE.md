# BridgePay

Instant-credit / BNPL underwriting platform. Portfolio project + potential product. Full architecture, DB schemas, Kafka contracts, API contracts, ML plan, and K8s layout live in `docs/bridgepay-platform-spec.md` — read it before any architectural work. It's deliberately not imported here so a trivial single-file task doesn't load the whole spec into context.

Progress tracker: @docs/PROGRESS.md

## Stack
- Backend: Java 21, Spring Boot 4.1.1, Maven. One Maven project per service under `services/`.
- Frontend: Angular, standalone components + signals. Not started yet.
- Data: one shared Postgres instance, schema-per-service. No cross-schema foreign keys, no cross-schema JPA relationships.
- Messaging: Kafka (KRaft mode), transactional outbox pattern.
- Auth: Keycloak (OIDC). Realm roles live under the `realm_access.roles` claim — the default Spring Security JWT converter doesn't read this, every service needs its own converter (see `application-service`'s `SecurityConfig` for the reference implementation).
- Deploy target: k3s on a single OCI VM.Standard.A1.Flex instance (ARM64/Ampere), 4 OCPU / 24GB.

## Conventions that apply to every service
- UUIDv7 for all primary keys via `com.github.f4b6a3:uuid-creator` (`UuidCreator.getTimeOrderedEpoch()`). Never `UUID.randomUUID()`.
- `@Version` optimistic locking on any row that can be updated from two different code paths.
- JPA auditing (`@CreatedDate`/`@LastModifiedDate`) via `@EnableJpaAuditing`.
- Every service ships two security configs: a real `SecurityConfig` (`@Profile("!local")`, JWT resource server) and a `LocalDevSecurityConfig` (`@Profile("local")`, permitAll) for running via `docker-compose` without Keycloak. `@PreAuthorize`-protected endpoints still enforce role checks even under the `local` profile — that's expected, method security is independent of the HTTP filter chain, not a bug to fix.
- Global error shape via `@RestControllerAdvice`: `{error, message, traceId, timestamp}`.
- `spring.threads.virtual.enabled=true` on every service.
- Docker images build for `linux/arm64` specifically. Always `docker buildx build --platform linux/arm64`, never a plain `docker build`, or the image won't run on the target instance.

## Spring Boot 4.1.1
This project hit real Boot 4 modularization breakage (renamed starters, relocated test-autoconfigure classes) while building the first two services. Full details are in `.claude/rules/spring-boot-4-migration.md`, auto-loaded whenever you're touching a `pom.xml` or `.java` file under `services/`. If a Spring class that used to exist throws `cannot find symbol`, search for the current Boot 4 location before guessing — don't assume Boot 3-era package layouts.

## Commands
- Build + test one service: `cd services/<service> && mvn clean verify`
- Run one service locally: `cd services/<service> && docker-compose up --build` (uses the `local` profile, no Keycloak/Kafka required — see that service's own README for specifics)

## Don't
- Add a foreign key or JPA relationship across service schemas — cross-service references are plain UUID columns, validated at the application layer only.
- Add Kafka to Applicant Service. By design it neither publishes nor consumes any topic.
- Reach for Terraform, Helm, Prometheus/Grafana, or Avro/Schema Registry unless explicitly asked. All deliberately deferred — see the spec doc's "out of scope for v1" notes.
