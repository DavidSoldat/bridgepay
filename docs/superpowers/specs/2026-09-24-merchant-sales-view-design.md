# Merchant Sales View — Design

## Context

Second of four planned "next expansion" sub-projects (shopper-facing →
merchant-facing → risk/ML → platform/ops, per user direction; k3s manifests
come after all four). Platform spec §4 calls for a merchant dashboard showing
"approved sales, payout ledger status". Only the payout ledger half exists
today: `main-app`'s `PayoutLedger` at `/merchant`, backed by
`GET /api/v1/merchants/{id}/payouts`. A merchant cannot see the checkouts
made at their store, their outcomes, or any totals.

This design adds a merchant **Sales** page (summary tiles + a filterable,
paginated list of every checkout at that merchant) and the two
`application-service` endpoints it needs.

Scope decisions made during brainstorming:
- In: sales list, summary tiles.
- Out: payout-ledger fixes (pagination/sort/linking), merchant checkout
  integration settings (API keys/webhooks).
- Merchants see **every outcome** (approved / in review / declined) but
  **no shopper credit data** — no `applicantId`, `riskScore`, or
  `scoreFactors`, matching how real BNPL providers treat merchants.

## Backend: application-service

No gateway or `docker-compose.yml` changes: `/api/v1/merchants/**` is
already routed to `application-service`.

### MerchantController

Extract the existing inline `merchantId`-claim check from `payouts(...)`
into one private helper (`requireOwnMerchant(Jwt jwt, UUID id)`, throws
`AccessDeniedException` → 403). All three endpoints call it and all three
keep `@PreAuthorize("hasRole('MERCHANT')")`.

### GET /api/v1/merchants/{id}/sales

```
GET /api/v1/merchants/{id}/sales?status=ALL|APPROVED|MANUAL_REVIEW|DECLINED&page=&size=
    -> Page<MerchantSaleResponse>, newest first (createdAt desc)
```

- `status` defaults to `ALL`. Any other value is parsed with
  `ApplicationStatus.valueOf`; an unknown value throws
  `IllegalArgumentException`, which the existing `GlobalExceptionHandler`
  already maps to `400 VALIDATION_ERROR`.
- New record `MerchantSaleResponse(UUID id, Instant createdAt,
  BigDecimal amount, String status, Integer installmentCount,
  BigDecimal installmentAmount, Instant decisionAt)`. It is its own DTO, not
  a reuse of `ApplicationResponse`, so credit fields cannot leak by accident.
- New repository methods on `CreditApplicationRepository`:
  `findByMerchantIdOrderByCreatedAtDesc(UUID, Pageable)` and
  `findByMerchantIdAndStatusOrderByCreatedAtDesc(UUID, ApplicationStatus, Pageable)`.
- New service method `CreditApplicationService.listSalesForMerchant(UUID merchantId, String status, Pageable)`.

### GET /api/v1/merchants/{id}/summary

```
GET /api/v1/merchants/{id}/summary -> MerchantSummaryResponse
```

```
MerchantSummaryResponse(
    long totalCheckouts,
    long approvedCount,
    long inReviewCount,      // MANUAL_REVIEW
    long declinedCount,
    Double approvalRate,     // approved / (approved + declined); null if both are 0
    BigDecimal approvedVolume, // sum of amount over APPROVED applications
    BigDecimal feesPaid,     // sum of merchant_payouts.fee_amount
    BigDecimal netPaidOut    // sum of merchant_payouts.amount - feesPaid
)
```

- All-time, no date range.
- `approvalRate` deliberately excludes in-review applications: they have no
  outcome yet, and counting them as "not approved" would understate the rate.
- Two queries: one `SELECT status, COUNT(*), SUM(amount) ... WHERE merchant_id = ? GROUP BY status`
  on `applications`, one `SELECT SUM(amount), SUM(fee_amount) ... WHERE merchant_id = ?`
  on `merchant_payouts` (JPQL `@Query`s). Sums default to `0` when there are no rows.
- `COMPLETED`/`DEFAULTED` exist in `ApplicationStatus` but nothing in
  `application-service` ever sets them; the summary counts them only toward
  `totalCheckouts`. Mark this with a `ponytail:` comment naming the upgrade
  (fold them into approved counts/volume once something sets them).
- New service method `CreditApplicationService.summaryForMerchant(UUID merchantId)`.

### Migration

`V3__index_applications_merchant_id.sql`:
`CREATE INDEX idx_applications_merchant_id ON application.applications (merchant_id);`
(Only `applicant_id` and `status` are indexed today; both new endpoints filter by merchant.)

## Frontend: main-app

### Routes and navigation

```
/merchant          -> Sales (new; merchant landing page, merchantGuard)
/merchant/payouts  -> PayoutLedger (moved from /merchant, merchantGuard)
```

`rootRedirectGuard` is unchanged (still sends merchants to `/merchant`).
The sidebar's single "Payouts" entry becomes two: "Sales" (`/merchant`, with
`routerLinkActiveOptions: { exact: true }` so it doesn't stay highlighted
on `/merchant/payouts`) and "Payouts" (`/merchant/payouts`).

### Data service

New `merchant/sales.ts` (`Sales` data service, sibling of `payouts.ts`):
- `list(merchantId, status, page, size)` → `Page<MerchantSaleResponse>`
- `summary(merchantId)` → `MerchantSummaryResponse`

Models go in `shared/models/` next to `merchant-payout.ts`.
(The component is named `SalesPage` to avoid clashing with the service name.)

### SalesPage component (`merchant/sales-page/`)

- **Tiles** (top row, 4): Approved volume, Approval rate (shown as `—` when
  `null`), Fees paid, Net paid out. Counts (`approved / in review /
  declined` out of `totalCheckouts`) shown as a small line under the tiles.
  Loaded once.
- **Filter strip**: All / Approved / In review / Declined buttons driving a
  `filter` signal, same pattern as `ReviewQueue`. Default: All.
- **Table**: Date, Amount, Plan (`4 × 49.50`), Status (colored dot + label,
  same styling as `PayoutLedger`), Decided. Newest first.
- **Pagination**: Prev / Next buttons plus "Page X of Y", driven by a
  `page` signal and the response's `totalPages`. Changing the filter resets
  `page` to 0. Fetch is `toObservable(computed({filter, page})).pipe(switchMap(...))`.
- **Errors**: separate `summaryError` and `listError` signals using the
  existing `loadError` pattern, so a failing summary doesn't hide the list
  and vice versa.

## Error handling

| Case | Result |
|------|--------|
| No / non-merchant role | 403 (method security) → component error message |
| `merchantId` claim ≠ path `id` | 403 via `requireOwnMerchant` |
| Unknown `status` | 400 `VALIDATION_ERROR` |
| Merchant with zero applications | 200, empty page / zeroed summary, `approvalRate: null` |

## Testing

TDD throughout: watch each test fail first.

Backend (`application-service`, real Testcontainers Postgres, no mocked DB):
- `MerchantControllerIntegrationTest` (extend or create):
  - sales list returns only this merchant's applications, newest first
  - sales JSON contains **no** `applicantId`, `riskScore`, `scoreFactors` keys
  - `status=DECLINED` filters; `status=BOGUS` → 400
  - another merchant's `id` → 403 for both new endpoints
  - summary numbers match seeded rows (mixed statuses + payouts), and
    `approvalRate` is `null` with zero decided applications
- `CreditApplicationServiceTest`: approval-rate math and the null case.

Frontend (`npx ng test --watch=false`):
- `sales.spec.ts`: correct URLs and params.
- `sales-page.spec.ts`: tiles render values and `—` for null rate; filter
  click refetches with status and resets to page 0; Next/Prev change page and
  disable at the edges; error messages for summary and list failures shown
  independently.
- Routes/sidebar: `/merchant/payouts` renders `PayoutLedger`.

Full suites green: `mvn clean verify` (application-service) and `ng test` (main-app).

## Non-goals

- Payout ledger pagination/sorting/linking (explicitly out of scope).
- Date-range filtering on tiles or list.
- Merchant checkout integration settings (API keys, webhooks).
- Any change to other services, the gateway, or `docker-compose.yml`.
