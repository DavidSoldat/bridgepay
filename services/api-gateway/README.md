# BridgePay — API Gateway

The single `/api/v1/**` entry point in front of Applicant Service and
Application Service (spec section 4/7/13): path-based routing, a global
rate limit, and gateway-level JWT validation. Internal-only endpoints
(`/internal/**` on any service) are never routed here at all - they're
unreachable from outside the cluster at the network level, not just
auth-gated (spec section 11).

## Routes

| Path | Target |
|---|---|
| `/api/v1/applicants/**` | Applicant Service |
| `/api/v1/applications/**` | Application Service |
| `/api/v1/merchants/**` | Application Service |

## Run locally

```bash
docker-compose up --build
```

Uses the `local` Spring profile (JWT auth disabled) and defaults to
`host.docker.internal:8080`/`host.docker.internal:8081` for the two
downstream services - run their own `docker-compose.yml` files alongside
this one (so they're reachable on the host), or run the whole stack from
the repo root instead.

## Run tests

```bash
mvn clean verify
```

`RoutingIntegrationTest` and `SecurityIntegrationTest` stub the two
downstream services with WireMock rather than requiring them to actually
run. `CorrelationIdFilterTest`/`RateLimitFilterTest` are plain unit tests
against a fake `FilterChain`.

## Known gap

No route for Repayment Reconciliation Service's public `/webhooks/paddle`
endpoint (a non-`/api/v1` path) - it's reached directly for now. Revisit
when the k3s Ingress rules are written.

No CORS configuration exists yet. The two Angular apps (main app on
`localhost:4200`, storefront on `localhost:4201`, per the Keycloak
realm's registered client origins) will need
`spring.security.web.cors`/`CorsConfigurationSource` wiring in both
`SecurityConfig` and `LocalDevSecurityConfig` before real browser traffic
can reach this gateway. Revisit when the Main Angular app work starts.
