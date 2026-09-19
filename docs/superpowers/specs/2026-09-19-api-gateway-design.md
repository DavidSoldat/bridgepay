# API Gateway — Design

## Context

Next item on `docs/PROGRESS.md`. Per `docs/bridgepay-platform-spec.md` §4/§7/§9/§11/§13,
this is the 7th backend service — the single entry point for `/api/v1/**`
traffic in front of the six already-built services, doing routing, rate
limiting, and gateway-level JWT validation ("same role as the reference
project", §4). It was missing from `PROGRESS.md`'s tracked list entirely
until scoped here, discovered while planning the frontend work that depends
on it existing first.

Decisions made before writing this doc (see chat for full option sets):

- **Spring Cloud Gateway Server MVC**, not classic/reactive Gateway — the
  servlet-based flavor, so it stays on the same blocking + virtual-threads
  model (`spring.threads.virtual.enabled=true`) as every other service in
  this project, instead of introducing Netty/Project Reactor as the only
  reactive component here. Its actual compatibility with Boot 4.1.1 is
  unverified — same category of open risk as every other Boot 4 migration
  surprise logged in `.claude/rules/spring-boot-4-migration.md`; `mvn clean
  verify` will be the first real signal.
- **Rate limiting**: one global Resilience4j core `RateLimiter`, wired by
  hand — consistent with this project's standing call that no confirmed
  Boot 4-compatible Resilience4j Spring integration exists, so every
  resilience concern here is programmatic, not annotation-based. Not
  Redis-backed: spec §13 plans one gateway replica, so there's no shared
  state to coordinate.
- **Correlation ID propagation scope**: this service generates/forwards
  `X-Correlation-Id` and logs it. None of the 6 existing services read that
  header into their own MDC yet (confirmed — every one of them falls back to
  `UUID.randomUUID()` in `GlobalExceptionHandler` today, since nothing has
  ever set `MDC.get("traceId")`). Wiring each of those services to read the
  header is a separate, mechanical follow-up task, not bundled into "build
  the gateway."
- **Gateway does real JWT validation**, not pass-through: rejects
  missing/expired/malformed tokens with `401` before proxying anything
  downstream. It does not do role checks — no business logic lives here, so
  `@PreAuthorize` role enforcement stays exactly where it already is, on the
  6 downstream services. The original `Authorization` header is forwarded
  unchanged, so nothing downstream needs to change.

## Service shape

- `services/api-gateway/` — Java 21, Spring Boot 4.1.1, Maven, package
  `com.bridgepay.gateway`.
- Port `8086` (next free after Notifications Service's `8085`).
- No datastore, no Kafka — pure routing plus cross-cutting concerns, nothing
  to persist. (Per CLAUDE.md, Kafka is only banned outright for Applicant
  Service by design; this service simply has no event to publish or
  consume.)
- Standard `SecurityConfig` (`@Profile("!local")`, JWT resource server,
  same `realm_access.roles`-aware `JwtAuthenticationConverter` copied from
  Application Service's reference implementation) / `LocalDevSecurityConfig`
  (`@Profile("local")`, permitAll) pair. `spring.threads.virtual.enabled=true`,
  global `@RestControllerAdvice` error shape — same as every other service.
  No JPA/Flyway/`@Version`/UUIDv7 here — there are no entities.

## Routing

`spring.cloud.gateway.mvc.routes` in `application.yaml`, downstream base
URLs from env vars (same pattern every other service already uses for
inter-service URLs):

| Path | Target | Env var |
|---|---|---|
| `/api/v1/applicants/**` | Applicant Service | `APPLICANT_SERVICE_URL` (default `http://localhost:8080`) |
| `/api/v1/applications/**` | Application Service | `APPLICATION_SERVICE_URL` (default `http://localhost:8081`) |
| `/api/v1/merchants/**` | Application Service | `APPLICATION_SERVICE_URL` |

Downstream controllers already declare their own `/api/v1/...`
`@RequestMapping` prefix (confirmed in `ApplicantController` and
`CreditApplicationController`), so routes forward the path as-is — no
prefix stripping/rewriting.

`/api/v1/merchants/**` is routed even though `GET
/api/v1/merchants/{id}/payouts` doesn't exist in Application Service yet —
it's already committed in the platform spec's API table (§11), so routing
it now costs nothing; it 404s downstream until that endpoint is built, same
as any other not-yet-implemented route would.

Internal-only endpoints (`/internal/**` on any service) are never routed
here at all — per spec §11 they're unreachable from outside the cluster at
the network level, not just auth-gated, so the gateway simply has no route
for them.

No route for `/webhooks/paddle` — a public, non-`/api/v1` path on Repayment
Reconciliation Service. Wiring it into this gateway (vs. reaching that
service directly) is out of scope for this task; noted as a gap for
whoever writes the k3s Ingress/route rules later.

## Security

- `SecurityConfig`: `.requestMatchers("/actuator/health/**").permitAll()`,
  everything else `.authenticated()`, same `oauth2ResourceServer().jwt(...)`
  wiring as every other service. A request with no/expired/malformed token
  never reaches a downstream call.
- `LocalDevSecurityConfig`: permitAll, matching every other service's local
  profile — routing still works without Keycloak running locally.
- No new trust boundary is introduced: downstream services keep their own
  full JWT resource-server validation exactly as it is today (defense in
  depth), so this is additive, not a refactor of anything existing.

## Rate limiting

One global `io.github.resilience4j.ratelimiter.RateLimiter` (core library),
instantiated once at startup from config (`gateway.rate-limit.requests-per-second`,
`gateway.rate-limit.timeout-ms`), applied in a servlet `Filter` ahead of
routing. On `RequestNotPermitted`, returns `429` via the shared `ApiError`
shape (`{error: "RATE_LIMITED", message, traceId, timestamp}`).

Global rather than per-client/per-IP — a single gateway replica with no
adversarial traffic expected yet doesn't justify a keyed-limiter registry.
`// ponytail: global rate limit, switch to a per-client-IP keyed
RateLimiterRegistry if abuse/noisy-neighbor traffic ever shows up`.

## Correlation ID

A servlet `Filter`, ordered ahead of the rate limiter:

1. Read `X-Correlation-Id` from the incoming request; if absent, generate
   one (`UUID.randomUUID()` — this is a log correlation token, not a
   database primary key, so the UUIDv7 convention doesn't apply here).
2. `MDC.put("traceId", id)` for the gateway's own structured logs; cleared
   in a `finally` block.
3. Set `X-Correlation-Id` on both the outgoing (proxied) request and the
   response back to the client.

This makes the gateway's own logs and error responses carry a real trace
ID immediately. Downstream services picking the same header up into their
own MDC is tracked as a follow-up, not built here (see Non-goals).

## Error shape

Same `@RestControllerAdvice` → `{error, message, traceId, timestamp}`
contract as every other service (`ApiError`/`GlobalExceptionHandler`,
copied verbatim), covering:

| Case | Status | `error` |
|---|---|---|
| Missing/invalid/expired JWT | 401 | Spring Security's default `BearerTokenAuthenticationEntryPoint` response — empty body, not the shared `ApiError` shape. None of the other 6 services customize the `AuthenticationEntryPoint` either, so this is consistent project-wide, not a gateway-specific gap. |
| Rate limit tripped | 429 | `RATE_LIMITED` |
| No matching route | 404 | `NOT_FOUND` |
| Downstream connection failure/timeout | 502 | `BAD_GATEWAY` |

## docker-compose

- Root `docker-compose.yml`: new `api-gateway` service, `depends_on:
  applicant-service, application-service`, port `8086:8086`, env vars
  `APPLICANT_SERVICE_URL=http://applicant-service:8080`,
  `APPLICATION_SERVICE_URL=http://application-service:8081`,
  `KEYCLOAK_ISSUER_URI` (same default-port fix already applied to the other
  6 services).
- `services/api-gateway/docker-compose.yml`: same shape, pointing at
  `host.docker.internal:8080`/`host.docker.internal:8081` for solo use
  alongside those two services run separately on the host — no
  Postgres/Kafka/Redis needed for this service's own compose file.

## Testing

Sized to this service's small surface (comparable to Mock Credit Bureau's
7 tests — no JPA/Flyway/Kafka/Redis here either):

- Unauthenticated request to a real route → `401` (Spring Security's
  default empty-body response, not the shared `ApiError` shape).
- Valid JWT + routed request reaches a stubbed downstream (WireMock) and
  the response passes through unchanged.
- Rate limiter: N+1th request within the window → `429`.
- Correlation ID: request without the header gets one generated, forwarded
  downstream (asserted against the WireMock stub's received headers), and
  echoed on the response; request that already has the header keeps the
  same value end to end.
- Local profile: same routing test as above but without a JWT, confirming
  `LocalDevSecurityConfig` doesn't block it.

## Non-goals for v1

- No propagation of `X-Correlation-Id` into the 6 existing services' MDC —
  tracked as a separate follow-up task.
- No per-client/per-IP rate limiting — global limiter only (see `ponytail:`
  note above).
- No route for Repayment Reconciliation's public `/webhooks/paddle` — that
  path reaches its service directly for now; revisit when the k3s Ingress
  rules are written (spec §13 already routes `/api/**` → gateway at the
  Ingress level, which wouldn't cover a non-`/api/**` path anyway).
- No response caching, no request/response transformation, no
  GraphQL/gRPC — plain HTTP path-based proxying only, matching spec §4's
  stated scope ("routing, rate limiting, security validation").
