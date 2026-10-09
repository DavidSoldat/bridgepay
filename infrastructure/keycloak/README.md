# BridgePay — Keycloak realm

`bridgepay-realm.json` is the `bridgepay` realm, exported from a real Keycloak (`kc.sh export`) and
imported on first start. It contains **no signing keys**: Keycloak generates its own on import, and
CI fails if a `privateKey` or `secret` ever appears in the file (the repository is public).

## Roles and demo users

| Username | Role | Notes |
|---|---|---|
| `shopper1` | `shopper` | |
| `ops1` | `ops` | |
| `merchant1` | `merchant` | `merchantId` claim = `00000000-0000-7000-8000-000000000001`, the seeded demo merchant |

Passwords equal the usernames. They're published on the landing page on purpose: this is a public
demo. Demo users can't change their password or profile (the account console roles are removed from
the realm's default roles), and there is no self-registration.

Roles are lowercase realm roles in the `realm_access.roles` claim. Every service maps them to Spring
authorities with its own converter.

## Clients

Both public (no secret), PKCE required:

| Client | App | Production redirect URI |
|---|---|---|
| `main-app` | ops and merchant app | `https://app.bridgepay.duckdns.org/*` |
| `storefront` | shop | `https://shop.bridgepay.duckdns.org/*` |

## Login theme

`themes/bridgepay/login/` is a CSS-only reskin of Keycloak's `keycloak.v2` theme: brand colours,
fonts and logo through the CSS custom properties Keycloak documents. No FTL template is touched, so
the login form and flow are stock Keycloak. The logo SVGs come from `../../assets/brand/`.

In production only `/realms/bridgepay/` and `/resources/` are routed to Keycloak; the admin console
and the master realm are not reachable from the internet.
