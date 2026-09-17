# Keycloak Realm Setup — Design

## Context

First of four sub-projects that make up "frontend" work (the other three:
Spring Cloud Gateway, main Angular app, storefront Angular app — each gets
its own brainstorm/spec/plan). Nothing else in that chain works without a
real realm to authenticate against: the gateway's JWT validation and both
Angular apps' OIDC login both need this first.

Decided before implementation:
- **Roles are lowercase** (`shopper`, `merchant`, `ops`) — `CreditApplicationController`
  has two different role checks: `@PreAuthorize("hasRole('OPS')")` (works via
  the JWT converter's uppercasing) and a raw `realm_access.roles` claim check
  (`.contains("ops")`, case-sensitive). Only lowercase realm roles satisfy
  both without touching that code.
- **A demo merchant seed row was added** (`services/application-service/src/main/resources/db/migration/V2__seed_demo_merchant.sql`,
  fixed id `00000000-0000-7000-8000-000000000001`) so the `merchant1` demo
  user's `merchantId` claim points at something real.
- **Local Keycloak runs on port 8180** (8080 is Applicant Service's), backed
  by the **same shared Postgres** instance in its own `keycloak` schema — the
  platform spec already calls for this ("Keycloak gets a schema on the same
  server too").
- Every service's `KEYCLOAK_ISSUER_URI` default was fixed — they previously
  pointed at copy-paste-wrong ports (e.g. Application Service's default
  pointed at its own port, 8081). All six now default to
  `http://localhost:8180/realms/bridgepay`.

## Realm shape

Realm `bridgepay`, roles `shopper` / `merchant` / `ops`.

**Clients** — both public SPA clients (no secret, PKCE `S256`, standard flow,
direct-access-grants also enabled purely for local curl-based testing):

| Client | Redirect URI | Purpose |
|---|---|---|
| `main-app` | `http://localhost:4200/*` | Merchant + ops dashboards |
| `storefront` | `http://localhost:4201/*` | Shopper signup + checkout |

**Custom claim**: `main-app` has a `merchantId` protocol mapper (user
attribute → JWT claim), since the merchant dashboard needs to scope payouts
to its own `merchantId` per spec §11. Required declaring `merchantId` as a
User Profile attribute first (admin-only view/edit) — Keycloak 26's User
Profile feature silently drops any attribute not declared there, which cost
real debugging time before being found.

**Demo users** (all with a matching-username password, e.g. `merchant1`/`merchant1` —
dev-only, never used this pattern for anything real):

| Username | Role | Notes |
|---|---|---|
| `shopper1` | `shopper` | |
| `merchant1` | `merchant` | `merchantId` attribute = the seeded demo merchant's id |
| `ops1` | `ops` | |

All three needed `firstName`/`lastName` set explicitly — Keycloak's default
User Profile schema requires them, and their absence silently produces
`invalid_grant: Account is not fully set up` on login rather than a clear
validation error at user-creation time.

## How this was actually built

Not hand-authored: a real Keycloak container was started against the shared
Postgres, the realm/roles/clients/mapper/users were configured via
`kcadm.sh`, tokens were fetched and decoded to confirm the `realm_access.roles`
and `merchantId` claims actually appear correctly, then the realm was
exported (`kc.sh export`) and the resulting file committed as
`keycloak/bridgepay-realm.json`. The compose service runs
`start-dev --import-realm` with that file bind-mounted to
`/opt/keycloak/data/import`, so a fresh environment self-provisions.

**Verified, not assumed**: after committing the export, the `keycloak`
Postgres schema was dropped and recreated from scratch, Keycloak was
restarted, and all three demo users successfully logged in again — proving
the committed file actually round-trips, not just that it was produced.

**Verified against a real (non-`local`-profile) service**: ran Applicant
Service with its actual `SecurityConfig` (JWT resource server, not the
`local` profile's `permitAll`) against this Keycloak. A request with no
token got `401`; a request with a real signed token for `shopper1` got `404`
(correctly authenticated — just no applicant profile exists yet for that
user, which is the correct behavior, not a bug). This is the first proof
anywhere in this project that a real (non-mocked, non-local-profile) JWT
actually validates end-to-end against one of these services.

## Non-goals for this sub-project

- No gateway yet — that's the next sub-project. Nothing here assumes it exists.
- No HTTPS/hostname configuration — local dev only, `KC_HOSTNAME_STRICT=false`,
  plain HTTP. Real TLS/hostname config is a k3s-milestone concern (spec §13
  already covers cert-manager + Let's Encrypt for the real deployment).
- No fine-grained authorization (Keycloak's resource/permission model) —
  role-based checks only, matching what's already coded.
