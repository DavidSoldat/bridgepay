# BridgePay — Keycloak realm

`bridgepay-realm.json` is a real Keycloak export (via `kc.sh export`), not
hand-authored — see `docs/superpowers/specs/2026-09-17-keycloak-realm-design.md`
for the full design and how it was built/verified.

## Run locally

Included in the root `docker-compose.yml` — `docker-compose up keycloak` (or
just run the whole stack). Auto-imports this file on startup via
`start-dev --import-realm`. Admin console: http://localhost:8180 (`admin`/`admin`,
dev-only credentials).

## Demo users (dev-only, do not reuse this password pattern anywhere real)

| Username | Password | Role | Notes |
|---|---|---|---|
| `shopper1` | `shopper1` | `shopper` | |
| `merchant1` | `merchant1` | `merchant` | `merchantId` claim = `00000000-0000-7000-8000-000000000001` (the seeded demo merchant, see Application Service's `V2__seed_demo_merchant.sql`) |
| `ops1` | `ops1` | `ops` | |

## Clients

Both public (no secret), PKCE required:

| Client | Redirect URI |
|---|---|
| `main-app` | `http://localhost:4200/*` |
| `storefront` | `http://localhost:4201/*` |

## Regenerating this file

If the realm needs to change (new role, new client, new mapper), configure
it against a running Keycloak via `kcadm.sh` or the admin console, then
re-export:

```bash
docker-compose stop keycloak
docker-compose run --name kc-export --entrypoint "" keycloak \
  /opt/keycloak/bin/kc.sh export --dir /tmp/export --realm bridgepay --users realm_file
docker cp kc-export:/tmp/export/bridgepay-realm.json ./keycloak/bridgepay-realm.json
docker rm kc-export
```

Then verify it actually round-trips before committing: drop the `keycloak`
Postgres schema, recreate it empty, restart Keycloak, and confirm the demo
users can still log in.
