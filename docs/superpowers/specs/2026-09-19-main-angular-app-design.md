# Main Angular App — Design

## Context

Third of four "frontend" sub-projects (after Keycloak realm export and the
API Gateway, both done), per `docs/bridgepay-platform-spec.md` §4/§7: the
merchant dashboard (approved sales, payout ledger status) and ops/review
dashboard (applications in the "review" band, with score-breakdown
explainability) as role-gated views in one app, not separate frontends.

**Backend gap found and closed first** (commit `3886cce`, a bounded
sub-task before this spec): Application Service's `ApplicationResponse`
didn't expose `applicantId`/`merchantId`/`amount`/`riskScore`/
`scoreFactors` at all (present on the `applications` table, never
returned over HTTP), and `GET /api/v1/merchants/{id}/payouts` didn't exist
— only the JPA entities did. Both dashboards now have a real, complete API
to call.

Decisions made before writing this doc (see chat for full option sets and
the version research behind them):

- **Angular 21**, not 22. Angular 22 (current stable, June 2026) needs
  Node `^22.22.3`; this machine runs `v22.17.1`. Angular 21 (LTS through
  May 2027) needs only `^22.12.0`, which the current Node satisfies with
  no `EBADENGINE` warnings, and already has everything this app needs —
  standalone-by-default, signals, the official `ng add tailwindcss` flow.
  Upgrading Node instead was offered and declined.
- **`keycloak-angular@21.0.0`** (pinned explicitly — its `latest` npm tag
  is `22.0.0`, which peer-depends on `@angular/core@^22` and would silently
  break the Angular 21 pin) over a generic OIDC client
  (`angular-auth-oidc-client`). This backend is Keycloak-specific
  everywhere already (`realm_access.roles`, the `merchantId` claim), so a
  generic OIDC abstraction buys nothing — there's no plan to ever swap
  identity providers.
- **Tailwind CSS v4** via `ng add tailwindcss` (CSS-first config, no
  `tailwind.config.js`) — per the standing memory from 2026-09-17,
  confirmed still the officially-supported Angular integration path.
- **`frontend/main-app`**, not `services/main-app` — a deliberate
  divergence from this repo's usual `services/` convention, since this is
  a fundamentally different stack (static/nginx, not a JVM service) and
  the user asked for it explicitly.
- **Plain `HttpClient` + `toSignal()`** (`@angular/core/rxjs-interop`) for
  data fetching, not the `resource()`/`httpResource` APIs — those are only
  *stabilized* in Angular 22 (developer-preview in 21); `toSignal()` is
  long-stable and the established idiom for this Angular version.
- **No NgRx, no other state management library** — signals-based services
  are enough for two small dashboards; nothing here needs cross-route
  shared mutable state complex enough to justify a store.

## App shape

- `frontend/main-app/` — Angular 21, standalone components, TypeScript,
  scaffolded via `ng new` (not hand-rolled files), package manager npm
  (matching what's already resolvable in this environment).
- Tailwind v4 via `ng add tailwindcss` immediately after scaffolding.
- Angular CLI's default unit test setup (Karma/Jasmine) — not replaced.
- No custom ESLint/Prettier config beyond whatever `ng new` scaffolds by
  default — nothing in this project's conventions calls for more.

## Auth

- `keycloak-angular@21.0.0` initialized against the `bridgepay` realm's
  `main-app` client (already exported in `keycloak/bridgepay-realm.json`:
  public PKCE client, redirect `http://localhost:4200/*`).
- **Redirect login**, not silent-check-sso: an unauthenticated visitor is
  sent straight to Keycloak's own login page on app init. This is an
  internal dashboard, not a public page with an anonymous-browsing mode —
  there's nothing for an unauthenticated user to see.
- A functional `HttpInterceptorFn`, using keycloak-angular's own provided
  bearer-token interceptor (not hand-rolled), attaches the access token to
  every request whose URL matches the API Gateway's base URL.
- `AuthService` wraps the Keycloak instance and exposes two signals read
  from the decoded token: `roles()` (from `realm_access.roles`) and
  `merchantId()` (from the `merchantId` claim, `null` for non-merchant
  users). Nothing else in the app touches the raw Keycloak instance or JWT
  directly — every role/claim check goes through this service.

## Routing & role-gating

Two role-gated route trees under one app, each behind a functional
`canActivate` guard reading `AuthService.roles()`:

| Path | Role required | View |
|---|---|---|
| `/ops` | `ops` | Manual-review queue + application detail/decision |
| `/merchant` | `merchant` | Payout ledger |

The root path (`/`) redirects to `/ops` or `/merchant` based on which role
the token holds. A token holding neither role (a `shopper` token
shouldn't reach this app at all, since the storefront demo app — not this
one — is where shoppers go) renders a plain "no access" page instead of a
redirect loop or a confusing blank screen.

## Data fetching

Two signals-based services, each wrapping `HttpClient` + `toSignal()`
against the API Gateway (`http://localhost:8086` in dev, via Angular's
environment files — hardcoded at build time for now; making this
runtime-configurable is a follow-up needed before a real k3s deployment
with a different gateway URL per environment, not solved here, matching
this project's habit of naming a gap rather than silently deferring it):

- **`ApplicationsService`**:
  - `listManualReview()` → `GET /api/v1/applications?status=MANUAL_REVIEW`
    (paginated `Page<ApplicationResponse>`, ops role).
  - `getApplication(id)` → `GET /api/v1/applications/{id}` — backs the
    detail view; the existing controller already serves this for ops
    tokens (`getForOps`), returning the full `ApplicationResponse`
    including `scoreFactors`.
  - `reviewDecision(id, decision, reviewerNote?)` → `POST
    /api/v1/applications/{id}/review-decision`.
- **`PayoutsService`**:
  - `listPayouts(merchantId)` → `GET /api/v1/merchants/{id}/payouts`
    (paginated `Page<MerchantPayoutResponse>`, merchant role, `id` always
    `AuthService.merchantId()` — never a value the user could otherwise
    influence, since the backend independently enforces this scoping too).

Both response shapes are typed against the real backend DTOs added in
commit `3886cce` (`ApplicationResponse`, `MerchantPayoutResponse`) —
TypeScript interfaces hand-mirrored field-for-field, not generated, since
this project has no OpenAPI/codegen pipeline anywhere yet and two small
interfaces don't justify introducing one.

## Views

**Ops review dashboard** (`/ops`):
- List: paginated table of `MANUAL_REVIEW` applications — amount,
  merchant, submitted/decision date.
- Detail: full application record plus `scoreFactors` rendered as a
  sorted (by `Math.abs(contribution)`, matching how the Credit Risk Engine
  itself already sorts them) horizontal bar list — the explainability
  view spec §4 asks for. Approve/Decline buttons call `reviewDecision`;
  an optional reviewer-note field is sent along per the existing
  `ReviewDecisionRequest` shape.

**Merchant dashboard** (`/merchant`):
- Paginated payout ledger table: amount, fee amount, status (`PENDING`/
  `PAID`), paid date.

## Visual design

Designed via the `frontend-design` skill against an explicit brief:
enterprise/back-office, not a SaaS product — restrained palette, no
gradients. Full rationale and self-critique against generic-AI-design
defaults is in the chat history; tokens below are what implementation
follows.

**Color** — functional, not decorative; used sparingly:
- `#FAFAF9` page background, `#1C1C1B` primary ink, `#6B6963` secondary
  ink (metadata/timestamps), `#D8D6D0` the one hairline border weight
  used everywhere.
- `#2F5D50` — the single accent (primary actions, active nav, links,
  focus ring, and the "approved/paid" status meaning).
- `#8A6A2F` (muted ochre, "review/pending") and `#8A3B32` (muted brick,
  "declined") — small dot indicators next to status text only, never
  filled badges.
- No gradients, no bright saturated fills anywhere.

**Type**:
- **IBM Plex Sans** for all interface text/headings/labels.
- **IBM Plex Mono** for every number — currency amounts, dates,
  application/merchant IDs — functionally justified (tabular figures
  need to align and scan like a real ledger), not a stylistic flourish.
  Prose is never monospaced; numbers are never proportional.
- No all-caps labels, no tracked-out eyebrows, no em-dash label
  constructions, no arrow-suffixed button text.

**Layout**: left-aligned, dense, 0–2px border radius (nothing
load-bearing rounded), zero drop shadows. Persistent left sidebar with
only the nav items the caller's role can reach (an `ops` token never
sees "Payouts"), a slim top bar showing the signed-in user, real
`<table>`-based data views rather than a grid of cards.

**The one designed moment**: the application-detail score-factors view
renders `scoreFactors` as diverging horizontal bars off a shared
zero-line (positive contributions right, negative left) — the actual
shape of signed logistic-regression coefficient contributions, matching
how real SHAP/coefficient-explainability plots look. This is the only
illustrated element in the app; every other view is type, tables, and
hairline rules. Spending the one deliberate visual choice here is
directly motivated by spec §4/§12 — this chart *is* the explainability
feature, not decoration bolted onto it.

Both views are read-mostly with one write action (the ops decision) —
nothing here needs optimistic updates or complex client-side caching;
a decision action just re-fetches the list on success.

## Packaging

- Multi-stage `Dockerfile`: a Node build stage (`ng build`) → an nginx
  stage serving the static output, same two-stage shape as every backend
  service's own Dockerfile (build stage / run stage), just with different
  base images.
- Wired into the root `docker-compose.yml` alongside the other 7 services,
  same convention (its own service block, depends on nothing at the
  container level — it's a static SPA calling the gateway over the
  browser, not over the compose network).
- Per spec §13's k3s networking section, this app is served at `/` behind
  the shared Ingress; that Ingress rule itself is out of scope here (the
  k3s manifests task, still pending on `docs/PROGRESS.md`'s list).

## Testing

- Angular CLI's default scaffolded spec files for every new
  component/service/guard (Karma/Jasmine) — real component/service tests,
  not skipped.
- `AuthService`/route guards tested against a faked Keycloak token shape
  (the three realm roles, with/without a `merchantId` claim), not a real
  Keycloak instance — no browser-based e2e/Keycloak-integration test in
  this pass, matching the project's existing "manual smoke test after
  automated tests" habit for anything that needs real infrastructure this
  can't spin up in a unit test.
- Manual smoke test once built: `docker-compose up --build` from the repo
  root (API Gateway + Application Service + Keycloak all real), log in as
  `ops1`/`merchant1` (both already seeded demo users), confirm each
  dashboard renders real data and the ops decision action actually changes
  an application's status via the real backend.

## Non-goals for this pass

- No storefront/shopper-facing app — that's the next, separate sub-project
  (`docs/PROGRESS.md`'s "Storefront demo Angular app").
- No runtime-configurable API base URL — build-time environment file only,
  flagged above as a pre-k3s follow-up.
- No OpenAPI/codegen pipeline for backend DTO types — hand-mirrored
  TypeScript interfaces, matching this project's "defer heavier infra"
  pattern used everywhere else (Terraform, Helm, Schema Registry).
- No e2e browser test suite (Cypress/Playwright) — manual smoke test only,
  same level of automated-test depth every other "frontend" sub-project
  gets in this pass.
