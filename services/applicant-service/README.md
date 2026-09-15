# BridgePay — Applicant Service

Shopper signup and identity profile. First service in the BridgePay platform —
see the full project spec for architecture, DB schemas, Kafka contracts, API
contracts, ML plan, and Kubernetes layout.

Deliberately has **no Kafka dependency** — per the event contracts, this
service neither publishes nor consumes any topic.

## Run locally

```bash
docker-compose up --build
```

This starts Postgres and the service using the `local` Spring profile, which
disables JWT auth entirely (see `LocalDevSecurityConfig`) so you can poke at
the endpoints without a running Keycloak. **This profile is for local
convenience only** — it is never used in tests and never used in any deployed
environment. The real security path (`SecurityConfig`) is what's actually
tested and deployed.

## Run tests

```bash
mvn clean verify
```

Runs unit tests (Mockito) and the integration test (Testcontainers Postgres +
a stubbed `JwtDecoder`, real Spring Security filter chain).

## Known things to double-check on first build

This was written without the ability to actually compile it (no Maven Central
access in the environment it was written in), so a few specific things are
worth confirming on your first `mvn clean verify`:

- **Spring Boot version** (`4.1.1` in `pom.xml`) — bump to whatever the current
  latest 4.x is if newer.
- **`uuid-creator` version** (`5.3.7`) — confirmed current as of writing, but
  worth a quick check.
- **The autoconfiguration exclude class name** in `application.yml`'s `local`
  profile block (`OAuth2ResourceServerAutoConfiguration`) — the package path
  was written from memory and is the one thing most likely to need a small
  fix if it doesn't match exactly.

Everything else (entity mapping, migration, security wiring, tests) should be
correct as written, but this is genuinely the first time it's been assembled
end-to-end, so treat `mvn clean verify` as the real verification step, not a
formality.

## Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/v1/applicants` | required | Create a profile for the authenticated Keycloak identity |
| GET | `/api/v1/applicants/me` | required | Fetch the caller's own profile |

The caller is expected to already be authenticated via Keycloak (Angular's
OIDC flow) before hitting either endpoint — this service never creates
Keycloak users itself, only the profile row linked to an existing identity.
