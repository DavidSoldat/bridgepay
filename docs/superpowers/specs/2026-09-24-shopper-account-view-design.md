# Shopper Post-Purchase Account View — Design

## Context

First of four planned "next expansion" sub-projects (shopper-facing →
merchant-facing → risk/ML → platform/ops, per user direction). Today a
shopper's only interaction with the platform is checkout itself — the
storefront shows a one-time result screen (approved/declined/manual-review)
and nothing else. There is no way for a shopper to come back and see their
purchase history or track what they still owe.

The underlying data already exists:
- `application-service` has every `CreditApplication` a shopper has made,
  but only exposes single-by-id (subject-checked) or an OPS-only status-filtered
  list — no "list mine" endpoint.
- `repayment-reconciliation-service`'s `repayment_plans`/`installments` tables
  (per-installment due date, amount, status) are exactly the data an
  installment schedule needs, but the only controller reading them
  (`RepaymentHistoryController`) returns aggregate stats only, is
  `/internal/**`, and was built for Credit Risk Engine's own scoring input —
  not shopper use.

This design adds the two missing read endpoints and a new `/account` page in
`frontend/storefront` (its first use of Angular Router).

## Backend: application-service

New endpoint on the existing `CreditApplicationController`:

```
GET /api/v1/applications/me?page=&size=
    -> Page<ApplicationResponse>, filtered to jwt.subject
```

- New repository method: `Page<CreditApplication> findByApplicantId(UUID applicantId, Pageable pageable)`
  on `CreditApplicationRepository`.
- New service method: `CreditApplicationService.listForApplicant(UUID applicantId, Pageable pageable)`,
  same `toResponse` mapping the other list/get methods already use.
- No new security config — this route already falls under the controller's
  existing `authenticated()` rule; no role check needed, just subject
  filtering (same pattern as the existing single-`{id}` GET).

## Backend: repayment-reconciliation-service

This service has no shopper-facing API today — only `/internal/**` (network
isolation) and `/webhooks/paddle` (HMAC). Its `SecurityConfig` already wires
a full `JwtAuthenticationConverter` "ready the moment a role-checked endpoint
lands here" (per its own doc comment) — that comment is now cashed in, though
this endpoint needs subject-ownership checking, not a role check.

New endpoint:

```
GET /api/v1/repayment-plans/{applicationId}
    -> 200 RepaymentPlanResponse   (plan.applicantId == jwt.subject)
    -> 403                         (plan exists, belongs to someone else)
    -> 404                         (no plan for this applicationId — declined/manual-review/pending)
```

New DTOs:
```java
record RepaymentPlanResponse(
    UUID planId, UUID applicationId, String status,
    BigDecimal totalAmount, int installmentCount, BigDecimal installmentAmount,
    List<InstallmentResponse> installments)

record InstallmentResponse(
    int sequenceNumber, LocalDate dueDate, BigDecimal amount,
    String status, Instant paidAt)
```

New method on `RepaymentPlanService`: `getForApplicant(UUID applicationId, UUID applicantId)`,
built entirely from repository methods that already exist —
`RepaymentPlanRepository.findByApplicationId` and
`InstallmentRepository.findByRepaymentPlanOrderBySequenceNumberAsc` — no new
repository methods needed here.

**`LocalDevSecurityConfig` change**: today this service's local config is the
plain `permitAll()`-only variant (it has never had an endpoint reading
`@AuthenticationPrincipal Jwt` before). It needs the same stub-JWT-filter
upgrade `applicant-service`'s `LocalDevSecurityConfig` already has — parse the
real, unvalidated `sub` claim out of whatever bearer token is present,
falling back to the fixed local-dev subject when none is — so the new
endpoint behaves like a real authenticated call under `local` profile instead
of NPE-ing. Copied pattern, not reinvented.

## Gateway

New route (this service has never been gateway-routed before):

```yaml
- id: repayment-plans
  uri: ${bridgepay.repayment-reconciliation-service.base-url}
  predicates:
    - Path=/api/v1/repayment-plans/**
```

Plus the matching `bridgepay.repayment-reconciliation-service.base-url` property
(`${REPAYMENT_RECONCILIATION_SERVICE_URL:http://localhost:8084}`, port 8084
per that service's own design doc).

## Frontend: storefront

First use of Angular Router in this app. The existing catalog/signup/checkout
flow (`app.ts`, a single signal-based step component) is untouched and stays
mounted at the root route — this is additive, not a rewrite.

- `provideRouter([...])` added to `app.config.ts`; root route renders the
  existing step-flow component as today, new `/account` route renders a new
  `Account` component.
- A minimal persistent top bar (new small component) shows a "My Account"
  link once `Auth.authenticated()` is true — added once, above both the
  root flow and `/account`, not duplicated per-page.
- `/account` triggers `Auth.login()` on load if not authenticated (same
  imperative-login pattern checkout already uses — no route guard needed for
  a single route).
- New `RepaymentPlans` service (`frontend/storefront/src/app/account/repayment-plans.ts`),
  mirroring the existing `Applications`/`Applicants` services' shape: one method,
  `getPlan(applicationId): Observable<RepaymentPlanResponse>`.
- `Applications` service (already exists, used today only for `checkout()`)
  gains `listMine(page, size): Observable<Page<ApplicationResponse>>`.
- `Account` component: fetches `listMine()` on load; renders each application
  with a status badge; approved applications are expandable, lazily calling
  `getPlan()` on first expand and rendering the installment table (due date,
  amount, paid/due/past-due badge per status).

## Error handling

- Not logged in on `/account` → Keycloak login redirect (existing pattern).
- No applications yet → empty state message.
- Application without a plan (`DECLINED`/`MANUAL_REVIEW`/pending) → no
  expand affordance; plan fetch isn't attempted.
- Fetch failure (either endpoint) → distinct error message via a `loadError`
  signal, same pattern as `main-app`'s `ReviewQueue`/`PayoutLedger`, so a
  down backend is visibly different from "nothing to show."

## Testing

TDD throughout, matching this project's existing convention:

- `application-service`: new repository/service tests for `findByApplicantId`/
  `listForApplicant`; controller integration test for `GET /api/v1/applications/me`
  (own applications only, another shopper's applications excluded).
- `repayment-reconciliation-service`: service test for `getForApplicant`
  (happy path, 403-on-mismatch via a thrown exception the controller maps,
  404-on-no-plan); controller integration test for the full endpoint;
  `LocalDevSecurityConfig` regression test mirroring `applicant-service`'s
  existing one (real bearer token's `sub` used; no-token falls back to
  fixed demo subject).
- `frontend/storefront`: new specs for `Account`, the top-bar component, and
  the two service additions (`listMine`, `getPlan`), following existing spec
  conventions in this app (e.g. `checkout/applications.spec.ts`).

## Non-goals for this task

- No pagination UI on the applications list (page 0/size 20 fixed, same
  deliberate simplification already accepted in `main-app`'s review
  queue/payout ledger).
- No shopper-initiated actions (no early payoff, no dispute-from-this-view —
  dispute flow is its own separately-queued sub-project).
- No push/email notification tie-in — this is a pull view only.
