# Storefront Demo App — Design

## Context

Fourth and last of the "frontend" sub-projects (after Keycloak realm export,
the API Gateway, and the Main Angular App, all done), per
`docs/bridgepay-platform-spec.md`'s "Mock merchant storefront — a minimal
standalone Angular page simulating an external merchant's checkout, the
'client' hitting the API gateway." Unlike the Main Angular App (an internal,
always-authenticated back office), this app plays the role of a real
merchant's storefront with BridgePay's "Pay in 4" checkout embedded in it —
so its whole shape, including *when* login happens, is different.

Decisions made before writing this doc (see chat for full option sets):

- **Login only at checkout, not on page load.** The storefront itself is
  publicly browsable — `keycloak-js`'s `check-sso` flow (silent, iframe-based,
  no visible redirect) establishes whether a session already exists without
  forcing one. Clicking "Pay in 4" on a product is what triggers a real login
  if the shopper isn't already authenticated. This is the opposite of the
  Main Angular App's `login-required` flow, and deliberately so — a real
  storefront's own pages are public; only the payment step needs identity.
- **Small fixed product catalog** (4 hardcoded items, no cart, no inventory)
  under a fictional small outdoor-goods brand, **Ridgeline Supply Co.** —
  enough to feel like a real storefront without building actual e-commerce
  machinery the platform spec never asked for.
- **Signup is conditional, not automatic.** After login, `GET
  /api/v1/applicants/me` decides the path: a 404 shows the signup form
  first (one-time identity collection, per spec §2's "instant 'pay in 4'
  decision instead of a formal loan application" promise); a 200 skips
  straight to the checkout confirmation. This is the first thing anywhere
  in this project to actually call `GET /me` — it's existed on Applicant
  Service since the very first service was built, unused until now.
- **No Angular Router for the checkout flow.** Catalog → signup (if needed)
  → confirm → result is a linear sequence driven by one local component
  state signal, not routed pages — there's nothing here that benefits from
  being URL-addressable, and the flow has to survive a real full-page
  redirect to Keycloak and back anyway (handled via `sessionStorage`, not
  router state, since the redirect itself reloads the app from scratch).
- **Same-origin API access from the start.** The Main Angular App shipped
  without this and its final review caught a real, demonstrated CORS
  blocker (the SPA couldn't fetch cross-origin from a real browser at all)
  that had to be fixed after the fact. This app applies that lesson
  up front: nginx proxies `/api/` to the gateway in Docker, and an Angular
  dev-server proxy does the same for local `ng serve` — both built in Task 1,
  not discovered as a bug later.
- **Two distinct visual identities, not one theme** — see "Visual design"
  below. The storefront shell (Ridgeline Supply Co.) and the BridgePay
  "Pay in 4" widget deliberately look like they belong to two different
  companies, because in a real integration they would.
- **Reuses every already-verified Angular 21 / `keycloak-angular@21.0.0` /
  Tailwind v4 fact** established while building the Main Angular App (exact
  `ng generate` file-naming conventions, Vitest as the default test builder,
  the real `provideKeycloak`/`createAuthGuard`/`KEYCLOAK_EVENT_SIGNAL` API
  surface) — none of that is re-verified here, it's already proven for this
  toolchain.

## App shape

- `frontend/storefront/` — Angular 21, standalone components, TypeScript,
  scaffolded via `ng new` (Angular CLI's own naming convention: a component
  named `product-catalog` generates `product-catalog.ts`/`.html`/`.css`/
  `.spec.ts` with class `ProductCatalog`, no `.component.` infix — same as
  the Main Angular App).
- Tailwind v4 via `ng add tailwindcss`.
- Angular CLI's default test builder (Vitest via `@angular/build:unit-test`,
  `ng test`).
- `keycloak-angular@21.0.0` (pinned exactly, same reason as the Main Angular
  App: its `latest` npm tag targets Angular 22).

## Auth

- Keycloak client: `storefront` (already exported in
  `keycloak/bridgepay-realm.json` — public PKCE client, redirect
  `http://localhost:4201/*`, realm `bridgepay`).
- `provideKeycloak({ config: { url, realm: 'bridgepay', clientId:
  'storefront' }, initOptions: { onLoad: 'check-sso', silentCheckSsoRedirectUri:
  <origin>/silent-check-sso.html, pkceMethod: 'S256' } })`. A
  `public/silent-check-sso.html` file (the standard `keycloak-js` iframe
  target — a one-line `parent.postMessage(location.href, location.origin)`
  page) makes the initial session check invisible: no full-page redirect on
  load, no flicker, just a quiet iframe check.
- No route guards, no `createAuthGuard` — nothing in this app is
  role-gated. The one authentication decision point is imperative: when
  "Pay in 4" is clicked, if `keycloak.authenticated` is false, call
  `keycloak.login({ redirectUri: <current origin> })` directly (this *is*
  a real full-page redirect, unlike the silent check-sso). Before calling
  it, the selected product's id is written to `sessionStorage` so the app
  can resume the checkout flow after the redirect back.
- Same bearer-token interceptor pattern as the Main Angular App
  (`includeBearerTokenInterceptor` + `createInterceptorCondition`), scoped
  to same-origin `/api/...` paths (see "Same-origin API access" below —
  there's no separate absolute-URL/interceptor-regex duplication to get
  wrong this time, since the URL is relative from the start).

## Same-origin API access (built in from Task 1, not retrofitted)

- `environment.gatewayBaseUrl = ''` from the start — every API call is a
  relative `/api/v1/...` path.
- `nginx.conf` (Docker): a `location /api/ { proxy_pass
  http://api-gateway:8086; ... }` block alongside the SPA's `try_files
  $uri $uri/ /index.html;` fallback — both written in Task 1's Dockerfile
  work, matching the Main Angular App's post-review-fix shape exactly, just
  present from the beginning here.
- `proxy.conf.json` + `angular.json`'s `serve.options.proxyConfig` for the
  equivalent `ng serve`-against-a-real-gateway local workflow.
- Root `docker-compose.yml`: new `storefront` service, port `4201:80`,
  `depends_on: [api-gateway]` — same shape as `main-app`'s entry.

## Checkout flow

One component-level signal, `step: 'catalog' | 'signup' | 'confirm' |
'result'`, drives the whole flow — no router involved:

1. **Catalog** (default step): 4 hardcoded products (name, price, a flat
   color swatch standing in for a photo — see "Visual design"). Each has a
   "Pay in 4" button.
2. Clicking "Pay in 4" on a product:
   - Stores `{ productId }` in `sessionStorage`.
   - If `keycloak.authenticated` is false: calls `keycloak.login(...)` (full
     redirect to Keycloak, then back to `/`).
   - If already authenticated: proceeds immediately to step 3 without a
     redirect.
3. **On app init** (covers both the "already authenticated" case and "just
   redirected back from login"): if `sessionStorage` holds a pending
   `productId` and `keycloak.authenticated` is true, resume the flow:
   - `GET /api/v1/applicants/me` — a 404 moves to the **signup** step; a
     200 skips straight to **confirm**.
4. **Signup step**: a form for `firstName`/`lastName`/`dateOfBirth`/`email`/
   `phone` (exactly `SignupRequest`'s fields — nothing else, since the real
   backend collects no payment details at signup at all, despite the
   platform spec's summary line mentioning "payment method": confirmed by
   reading `SignupRequest.java` directly, this is a corrected assumption
   from the spec's own imprecise wording, not a simplification made here).
   Submits `POST /api/v1/applicants`, then moves to **confirm**.
5. **Confirm step**: shows the selected product, its price, and the
   Pay-in-4 breakdown widget (4 nodes — see "Visual design" — labeled
   "today" plus 3 more at roughly weekly intervals, illustrative text only,
   since the checkout response itself carries no due-date data; real due
   dates are Repayment Reconciliation Service's concern, invisible to this
   app). A "Confirm purchase" button submits `POST /api/v1/applications`
   with a fresh client-generated `Idempotency-Key` (`crypto.randomUUID()`)
   and `{ merchantId: <Ridgeline's seeded merchant id>, amount: <product
   price> }`. `merchantId` is the same demo merchant row already seeded by
   `V2__seed_demo_merchant.sql` (`00000000-0000-7000-8000-000000000001`) —
   this app plays the role of that same seeded merchant, so a real
   completed purchase here is visible on the Main Angular App's
   `merchant1` payout ledger, a genuine cross-app integration point, not
   a coincidence.
   - `sessionStorage`'s pending `productId` is cleared once this request is
     sent (whatever the outcome), so a page refresh mid-flow doesn't
     resubmit.
6. **Result step**: renders based on `ApplicationResponse.status`:
   - `APPROVED`: "You're approved" plus the real `installmentAmount`/
     `installmentCount` from the response.
   - `MANUAL_REVIEW`: "Your order is under review" (no promise of a
     timeline the backend doesn't make).
   - `DECLINED`: "This purchase couldn't be approved for Pay in 4."
   - A "Back to shop" action returns to **catalog**.

## Data / models

Hand-mirrored TypeScript interfaces against the real backend DTOs (no
codegen, same convention as the Main Angular App):
- `SignupRequest` / `ApplicantResponse` (Applicant Service).
- `CheckoutRequest` (`merchantId`, `amount`) / `ApplicationResponse`
  (reusing the same shape the Main Angular App already defined and
  verified against `services/application-service/.../dto/ApplicationResponse.java`
  — this app only reads `status`/`installmentCount`/`installmentAmount`
  from it, ignoring the ops-only fields like `scoreFactors`).

Two services: `Applicants` (`getMyProfile()` → `GET /api/v1/applicants/me`,
`signUp(request)` → `POST /api/v1/applicants`) and `Applications`
(`checkout(idempotencyKey, request)` → `POST /api/v1/applications`).

## Visual design

Two deliberately distinct identities — see chat for the full rationale and
self-critique against generic-AI-design defaults.

**Ridgeline Supply Co. (the storefront shell)** — quiet on purpose, so the
BridgePay widget can be the one loud thing on the page:
- Color: `#FAFAF8` background, `#26241F` ink, `#E4E1DA` hairline borders.
- Type: **Fraunces** (warm serif) for product names/headlines, **Work
  Sans** for prices/body/UI chrome.
- Products render as a flat-color swatch (standing honestly in for a
  photo, not apologizing for one) plus name and price — no borrowed stock
  imagery.
- Sharp corners, generous whitespace, centered product grid.

**The BridgePay "Pay in 4" widget** — the actual subject of this whole app,
gets its own confident consumer-fintech identity:
- Color: `#4B3F72` (deep indigo-violet) accent on a cool, barely-tinted
  `#F6F4FB` card surface — distinct from both the storefront's warm
  neutrals and the Main Angular App's green/paper enterprise palette.
- Type: **Manrope** — a rounded, friendly geometric sans, used only inside
  the widget/checkout surfaces, never in the storefront shell.
- Rounded corners here, the deliberate opposite of the Main Angular App's
  sharp ones — a consumer payment moment reads as approachable, an
  underwriting tool reads as precise; different subjects get different
  physical languages on purpose.
- The one designed moment: the 4-installment breakdown as 4 connected
  nodes on a line — the first solid/filled ("today"), the next three
  outlined — rather than a generic progress bar, since that's literally
  what "Pay in 4" means.

## Testing

- Angular CLI's default scaffolded spec files (Vitest) for every new
  component/service.
- `Applicants`/`Applications` services tested via `HttpTestingController`
  (real request-shape assertions — method, URL, body — same rigor as the
  Main Angular App), not mocked-away logic.
- The checkout-flow component's step transitions tested against stubbed
  services: 404-on-`/me` → signup step shown; 200-on-`/me` → confirm step
  shown directly; each of `APPROVED`/`MANUAL_REVIEW`/`DECLINED` rendering
  its own result message.
- No browser-automation/e2e suite — same manual-smoke-test ceiling as
  every other "frontend" sub-project in this project so far.

## Packaging

- Multi-stage `Dockerfile`, same shape as the Main Angular App's
  (including its own already-learned fixes applied from the start: an
  explicit `npm install -g npm@11.6.1` before `npm ci` if this machine's
  pinned `packageManager` version requires it, a `.dockerignore`, and the
  nginx SPA-fallback + `/api/` proxy `nginx.conf` from Task 1 rather than
  discovered later).
- Root `docker-compose.yml`: `storefront` service on port `4201`,
  `depends_on: [api-gateway]`.

## Non-goals for this pass

- No cart, no multi-item checkout, no inventory — one product bought at a
  time, per the platform spec's "minimal" wording.
- No order history / no "my purchases" page for the shopper.
- No real product photography — flat color swatches are the deliberate,
  final answer, not a placeholder for later.
- No runtime-configurable API base URL beyond the same-origin proxy setup
  — matches the Main Angular App's same stated limitation.
- No e2e browser test suite.
