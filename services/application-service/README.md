# BridgePay — Application Service

Owns the checkout flow: idempotency handling, the synchronous call into the
Credit Risk Engine, the transactional outbox, and the shared "finalize
decision" path used by both the automated engine and ops manual review.

## Run locally

```bash
docker-compose up --build
```

Uses the `local` Spring profile (JWT auth disabled — see `LocalDevSecurityConfig`)
so you can poke at the checkout endpoint without Keycloak running. **Note:**
the ops endpoints (`@PreAuthorize("hasRole('OPS')")`) still reject requests
even in this profile — method security is independent of the HTTP filter
chain, so there's no local bypass for those specifically. Kafka being
unreachable locally is expected and harmless: the outbox publisher logs a
warning and retries on its next poll rather than failing.

The Credit Risk Engine doesn't exist yet, so every real checkout call will
fail over to `MANUAL_REVIEW` via the circuit breaker's fallback — that's
correct behavior, not a bug, until that service is built.

## Run tests

```bash
mvn clean verify
```

Unit tests cover the checkout/review-decision logic (mocked repos + credit
risk client), the circuit breaker's fallback behavior, and the outbox
publisher's success/failure handling. The integration test uses Testcontainers
Postgres, a stubbed `JwtDecoder`, and a stubbed `CreditRiskClient` (always
approves) to exercise the full HTTP path: checkout, idempotency replay,
missing-header validation, and ops role enforcement.

## Known things to double-check on first build

Same caveat as the Applicant Service — written without the ability to
compile it. Specific risk areas this time, roughly in order of how confident
I am something's slightly off:

- **Resilience4j**: deliberately using `resilience4j-circuitbreaker` (core,
  framework-agnostic) instead of `resilience4j-spring-boot3`, since the
  latter's dependency chain is still pinned to Spring Framework 6 and I
  couldn't confirm Boot 4 / Spring Framework 7 support. The circuit breaker
  is wired programmatically in `HttpCreditRiskClient` rather than via
  annotations - if a Boot4-compatible Resilience4j Spring integration exists
  by the time you build this, switching to it is a reasonable upgrade, not
  a requirement.
- **`JwtGrantedAuthoritiesConverter` / `JwtAuthenticationToken` package
  paths** in `SecurityConfig` — written from Spring Security 6-era memory.
  Spring Security 7 (paired with Boot 4) reportedly went through its own
  modularization pass; these specific class locations are the most likely
  spot for another `cannot find symbol` if anything moved.
- **`spring-boot-starter-flyway`** — confirmed as the correct replacement
  for `flyway-core`, but I couldn't confirm whether it also subsumes
  `flyway-database-postgresql` or whether that needs adding alongside it.
- Same Boot version / `uuid-creator` version caveats as before.

If you hit another `cannot find symbol`, paste it the same way as last time —
these are all findable with a targeted search rather than another guess.

## Design notes worth knowing

- **`applicantId` = the Keycloak subject, parsed as a UUID** (Keycloak
  subject IDs are UUIDs by default), not a resolved reference to the
  Applicant Service's own internal `id`. Deliberate: resolving that would
  mean a live cross-service call on the critical checkout path, which is
  exactly the kind of extra failure point the design otherwise avoids.
- **`score_factors` is stored as `TEXT` containing JSON**, not a native
  `jsonb` column — sidesteps depending on Hibernate's JSON type-mapping
  behavior in this specific Boot/Hibernate pairing, which isn't verified.
  Trivial to upgrade later.
- Installment amount is a flat `amount / 4`, not remainder-adjusted on the
  last installment — a known minor simplification, not an oversight.

## Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/v1/applications` | shopper, `Idempotency-Key` header required | Checkout / apply |
| GET | `/api/v1/applications/{id}` | shopper (own) or ops (any) | Status lookup |
| GET | `/api/v1/applications` | ops | Manual review queue |
| POST | `/api/v1/applications/{id}/review-decision` | ops | Approve/decline a manual-review application |
