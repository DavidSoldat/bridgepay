# Merchant Sales View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give merchants a Sales page in `main-app` (summary tiles + filterable, paginated list of every checkout at their store) backed by two new merchant-scoped `application-service` endpoints.

**Architecture:** `application-service` gains `GET /api/v1/merchants/{id}/sales` (paged, newest-first, status filter, credit-data-free DTO) and `GET /api/v1/merchants/{id}/summary` (two JPQL aggregate queries). Both go through one shared merchant-claim check in `MerchantController`. `main-app` gets a `Sales` data service and a `SalesPage` component at `/merchant`; `PayoutLedger` moves to `/merchant/payouts`.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Data JPA (Hibernate), Flyway, Testcontainers Postgres, MockMvc; Angular 21 (standalone + signals), Tailwind v4, Vitest via `ng test`.

**Spec:** `docs/superpowers/specs/2026-09-24-merchant-sales-view-design.md`

## Global Constraints

- Merchant responses must never contain `applicantId`, `riskScore`, or `scoreFactors`.
- Every new endpoint: `@PreAuthorize("hasRole('MERCHANT')")` plus the JWT `merchantId` claim must equal the path `{id}` (else 403).
- `status` param: `ALL` (default) or an `ApplicationStatus` name; unknown → `400 VALIDATION_ERROR` via the existing `IllegalArgumentException` handler.
- `approvalRate` = approved / (approved + declined); `null` when both are 0. All-time, no date range.
- No changes to the gateway, other services, or `docker-compose.yml`.
- Frontend: Tailwind utility classes and existing color tokens only (`accent`, `status-review`, `status-declined`, `ink-muted`, `hairline`); signals, standalone components.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Work happens in an isolated git worktree (superpowers:using-git-worktrees), merged to `main` at the end.

## Review Focus

1. **`Page` JSON shape** — the Prev/Next buttons read top-level `totalPages`. Spring Data 4.1.1 defaults to `DIRECT` serialization (verified), but a Boot property or future upgrade could switch to `VIA_DTO` (`{content, page:{...}}`) and silently break pagination. Task 1 asserts `$.totalPages` in the integration test.
2. **Merchant with zero checkouts** — expect 200 with zeroed totals and `approvalRate: null` (not a 500 from null `SUM`s). Task 2 has an integration test for this and a unit test for null payout sums.
3. **Changing the filter while on page > 0** — should go back to page 0 (otherwise a shorter filtered list shows an empty page). Task 4 tests Next → filter click → last call is `(status, 0)`.
4. **Summary fails but the list works (or the reverse)** — each half should show its own error and the other should still render. Task 4 tests both directions.
5. **Lowercase status (`?status=approved`)** — `ApplicationStatus.valueOf` is case-sensitive, so this returns 400. That matches the existing ops endpoint and the frontend always sends uppercase. Accepted as-is and pinned by the `BOGUS` test in Task 1; no extra code.

---

## File Structure

**application-service** (`services/application-service/`)
- Create `src/main/java/com/bridgepay/application/dto/MerchantSaleResponse.java` — merchant-safe sale row.
- Create `src/main/java/com/bridgepay/application/dto/MerchantSummaryResponse.java` — tile totals.
- Create `src/main/java/com/bridgepay/application/repository/MerchantStatusTotals.java` — JPQL projection (status, count, sum).
- Create `src/main/java/com/bridgepay/application/repository/MerchantPayoutTotals.java` — JPQL projection (gross, fees).
- Create `src/main/resources/db/migration/V3__index_applications_merchant_id.sql`.
- Modify `repository/CreditApplicationRepository.java`, `repository/MerchantPayoutRepository.java`, `service/CreditApplicationService.java`, `web/MerchantController.java`.
- Tests: modify `src/test/java/com/bridgepay/application/MerchantControllerIntegrationTest.java`, `src/test/java/com/bridgepay/application/service/CreditApplicationServiceTest.java`.

**main-app** (`frontend/main-app/src/app/`)
- Create `shared/models/merchant-sale.ts`, `shared/models/merchant-summary.ts`.
- Create `merchant/sales.ts` + `merchant/sales.spec.ts` (data service).
- Create `merchant/sales-page/sales-page.{ts,html,css,spec.ts}`.
- Create `app.routes.spec.ts`.
- Modify `app.routes.ts`, `app.html`, `app.spec.ts`.

---

### Task 1: Sales list endpoint (application-service)

**Files:**
- Create: `services/application-service/src/main/java/com/bridgepay/application/dto/MerchantSaleResponse.java`
- Create: `services/application-service/src/main/resources/db/migration/V3__index_applications_merchant_id.sql`
- Modify: `services/application-service/src/main/java/com/bridgepay/application/repository/CreditApplicationRepository.java`
- Modify: `services/application-service/src/main/java/com/bridgepay/application/service/CreditApplicationService.java`
- Modify: `services/application-service/src/main/java/com/bridgepay/application/web/MerchantController.java`
- Test: `services/application-service/src/test/java/com/bridgepay/application/MerchantControllerIntegrationTest.java`

**Interfaces:**
- Consumes: existing `CreditApplication` getters (`getId`, `getCreatedAt`, `getAmount`, `getStatus`, `getInstallmentCount`, `getInstallmentAmount`, `getDecisionAt`), `ApplicationStatus` enum.
- Produces:
  - `record MerchantSaleResponse(UUID id, Instant createdAt, BigDecimal amount, String status, Integer installmentCount, BigDecimal installmentAmount, Instant decisionAt)`
  - `CreditApplicationService.listSalesForMerchant(UUID merchantId, String status, Pageable pageable) : Page<MerchantSaleResponse>`
  - `MerchantController.requireOwnMerchant(Jwt jwt, UUID id)` (private, reused in Task 2)
  - HTTP: `GET /api/v1/merchants/{id}/sales?status=&page=&size=` → Spring `Page` JSON (`content`, `totalElements`, `totalPages`, `number`, `size` at top level)

- [ ] **Step 1: Make the test stub's decision depend on amount**

In `MerchantControllerIntegrationTest.TestOverrides`, replace the `stubCreditRiskClient` bean so tests can create every outcome through the real checkout path (existing tests use `100.00` and still get APPROVE):

```java
        @Bean
        @Primary
        CreditRiskClient stubCreditRiskClient() {
            // Amount picks the decision so tests can create every outcome through the real checkout path.
            return request -> {
                BigDecimal amount = request.amount();
                if (amount.compareTo(new BigDecimal("1000")) >= 0) {
                    return new ScoreResult(0.8, ScoreDecision.DECLINE, List.of());
                }
                if (amount.compareTo(new BigDecimal("500")) >= 0) {
                    return new ScoreResult(0.5, ScoreDecision.MANUAL_REVIEW, List.of());
                }
                return new ScoreResult(0.1, ScoreDecision.APPROVE, List.of());
            };
        }
```

- [ ] **Step 2: Write the failing tests**

Add to `MerchantControllerIntegrationTest`:

```java
    @Test
    void merchantSeesOwnSalesNewestFirst_withoutShopperCreditData() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("3.50")));
        Merchant other = merchantRepository.save(new Merchant("Merchant B", new BigDecimal("3.50")));
        checkout(merchant.getId(), "100.00");
        checkout(merchant.getId(), "1500.00");
        checkout(other.getId(), "200.00");

        mockMvc.perform(get("/api/v1/merchants/{id}/sales", merchant.getId())
                        .with(merchantJwt(merchant.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].amount").value(1500.00))
                .andExpect(jsonPath("$.content[0].status").value("DECLINED"))
                .andExpect(jsonPath("$.content[1].amount").value(100.00))
                .andExpect(jsonPath("$.content[1].status").value("APPROVED"))
                .andExpect(jsonPath("$.content[1].installmentCount").value(4))
                .andExpect(jsonPath("$.content[1].installmentAmount").value(25.00))
                .andExpect(jsonPath("$.content[0].applicantId").doesNotExist())
                .andExpect(jsonPath("$.content[0].riskScore").doesNotExist())
                .andExpect(jsonPath("$.content[0].scoreFactors").doesNotExist());
    }

    @Test
    void salesCanBeFilteredByStatus() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("3.50")));
        checkout(merchant.getId(), "100.00");
        checkout(merchant.getId(), "600.00");
        checkout(merchant.getId(), "1500.00");

        mockMvc.perform(get("/api/v1/merchants/{id}/sales", merchant.getId())
                        .param("status", "MANUAL_REVIEW")
                        .with(merchantJwt(merchant.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("MANUAL_REVIEW"));
    }

    @Test
    void unknownSalesStatusIsRejectedWith400() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("3.50")));

        mockMvc.perform(get("/api/v1/merchants/{id}/sales", merchant.getId())
                        .param("status", "BOGUS")
                        .with(merchantJwt(merchant.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void merchantIsBlockedFromAnotherMerchantsSales() throws Exception {
        Merchant merchantA = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("3.50")));
        Merchant merchantB = merchantRepository.save(new Merchant("Merchant B", new BigDecimal("3.50")));

        mockMvc.perform(get("/api/v1/merchants/{id}/sales", merchantB.getId())
                        .with(merchantJwt(merchantA.getId())))
                .andExpect(status().isForbidden());
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd services/application-service && mvn test -Dtest=MerchantControllerIntegrationTest`
Expected: the 4 new tests FAIL (404 / no handler for `/sales`); the 3 existing tests still PASS. Docker must be running for Testcontainers.

- [ ] **Step 4: Add the DTO**

`dto/MerchantSaleResponse.java`:

```java
package com.bridgepay.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A checkout as a merchant sees it. Deliberately its own type rather than a
 * trimmed ApplicationResponse, so shopper credit data (applicantId,
 * riskScore, scoreFactors) can't leak to merchants by accident.
 */
public record MerchantSaleResponse(
        UUID id,
        Instant createdAt,
        BigDecimal amount,
        String status,
        Integer installmentCount,
        BigDecimal installmentAmount,
        Instant decisionAt
) {
}
```

- [ ] **Step 5: Add repository methods**

In `CreditApplicationRepository`, add:

```java
    Page<CreditApplication> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Page<CreditApplication> findByMerchantIdAndStatusOrderByCreatedAtDesc(UUID merchantId, ApplicationStatus status,
                                                                          Pageable pageable);
```

(`merchantId` resolves to the `merchant.id` association path, same as the existing `MerchantPayoutRepository.findByMerchantId`.)

- [ ] **Step 6: Add the service method**

In `CreditApplicationService`, add the import `com.bridgepay.application.dto.MerchantSaleResponse` and, after `listPayoutsForMerchant`:

```java
    @Transactional(readOnly = true)
    public Page<MerchantSaleResponse> listSalesForMerchant(UUID merchantId, String status, Pageable pageable) {
        Page<CreditApplication> page = "ALL".equalsIgnoreCase(status)
                ? applicationRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)
                : applicationRepository.findByMerchantIdAndStatusOrderByCreatedAtDesc(
                        merchantId, ApplicationStatus.valueOf(status), pageable);
        return page.map(application -> new MerchantSaleResponse(
                application.getId(),
                application.getCreatedAt(),
                application.getAmount(),
                application.getStatus().name(),
                application.getInstallmentCount(),
                application.getInstallmentAmount(),
                application.getDecisionAt()
        ));
    }
```

- [ ] **Step 7: Add the endpoint and extract the claim check**

Replace the body of `MerchantController` (keep the constructor) with:

```java
    @GetMapping("/{id}/payouts")
    @PreAuthorize("hasRole('MERCHANT')")
    public Page<MerchantPayoutResponse> payouts(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, Pageable pageable) {
        requireOwnMerchant(jwt, id);
        return applicationService.listPayoutsForMerchant(id, pageable);
    }

    @GetMapping("/{id}/sales")
    @PreAuthorize("hasRole('MERCHANT')")
    public Page<MerchantSaleResponse> sales(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                            @RequestParam(defaultValue = "ALL") String status, Pageable pageable) {
        requireOwnMerchant(jwt, id);
        return applicationService.listSalesForMerchant(id, status, pageable);
    }

    private void requireOwnMerchant(Jwt jwt, UUID id) {
        String jwtMerchantId = jwt.getClaimAsString("merchantId");
        if (jwtMerchantId == null || !jwtMerchantId.equals(id.toString())) {
            throw new AccessDeniedException("Not authorized for this merchant's data");
        }
    }
```

Add imports: `com.bridgepay.application.dto.MerchantSaleResponse`, `org.springframework.web.bind.annotation.RequestParam`.

- [ ] **Step 8: Add the index migration**

`src/main/resources/db/migration/V3__index_applications_merchant_id.sql`:

```sql
-- Both merchant endpoints (sales list, summary) filter applications by merchant.
CREATE INDEX idx_applications_merchant_id ON application.applications (merchant_id);
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `cd services/application-service && mvn test -Dtest=MerchantControllerIntegrationTest`
Expected: all 7 PASS.

- [ ] **Step 10: Commit**

```bash
git add services/application-service
git commit -m "application-service: add merchant-scoped GET /api/v1/merchants/{id}/sales

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Summary endpoint (application-service)

**Files:**
- Create: `services/application-service/src/main/java/com/bridgepay/application/dto/MerchantSummaryResponse.java`
- Create: `services/application-service/src/main/java/com/bridgepay/application/repository/MerchantStatusTotals.java`
- Create: `services/application-service/src/main/java/com/bridgepay/application/repository/MerchantPayoutTotals.java`
- Modify: `services/application-service/src/main/java/com/bridgepay/application/repository/CreditApplicationRepository.java`
- Modify: `services/application-service/src/main/java/com/bridgepay/application/repository/MerchantPayoutRepository.java`
- Modify: `services/application-service/src/main/java/com/bridgepay/application/service/CreditApplicationService.java`
- Modify: `services/application-service/src/main/java/com/bridgepay/application/web/MerchantController.java`
- Test: `services/application-service/src/test/java/com/bridgepay/application/service/CreditApplicationServiceTest.java`
- Test: `services/application-service/src/test/java/com/bridgepay/application/MerchantControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `MerchantController.requireOwnMerchant(Jwt, UUID)` and the amount-driven test stub from Task 1 (`<500` approve, `500–999.99` manual review, `>=1000` decline).
- Produces:
  - `record MerchantStatusTotals(ApplicationStatus status, Long count, BigDecimal volume)`
  - `record MerchantPayoutTotals(BigDecimal gross, BigDecimal fees)` (either may be `null` when no payouts)
  - `CreditApplicationRepository.totalsByStatusForMerchant(UUID merchantId) : List<MerchantStatusTotals>`
  - `MerchantPayoutRepository.totalsForMerchant(UUID merchantId) : MerchantPayoutTotals`
  - `record MerchantSummaryResponse(long totalCheckouts, long approvedCount, long inReviewCount, long declinedCount, Double approvalRate, BigDecimal approvedVolume, BigDecimal feesPaid, BigDecimal netPaidOut)`
  - `CreditApplicationService.summaryForMerchant(UUID merchantId) : MerchantSummaryResponse`
  - HTTP: `GET /api/v1/merchants/{id}/summary` → the JSON of `MerchantSummaryResponse`

- [ ] **Step 1: Write the failing unit tests**

Add to `CreditApplicationServiceTest` (imports: `com.bridgepay.application.dto.MerchantSummaryResponse`, `com.bridgepay.application.repository.MerchantStatusTotals`, `com.bridgepay.application.repository.MerchantPayoutTotals`):

```java
    @Test
    void summaryForMerchant_computesApprovalRateFromDecidedApplicationsOnly() {
        when(applicationRepository.totalsByStatusForMerchant(merchantId)).thenReturn(List.of(
                new MerchantStatusTotals(ApplicationStatus.APPROVED, 3L, new BigDecimal("450.00")),
                new MerchantStatusTotals(ApplicationStatus.DECLINED, 1L, new BigDecimal("1200.00")),
                new MerchantStatusTotals(ApplicationStatus.MANUAL_REVIEW, 5L, new BigDecimal("3000.00"))));
        when(merchantPayoutRepository.totalsForMerchant(merchantId))
                .thenReturn(new MerchantPayoutTotals(new BigDecimal("450.00"), new BigDecimal("15.75")));

        MerchantSummaryResponse summary = service.summaryForMerchant(merchantId);

        assertThat(summary.totalCheckouts()).isEqualTo(9);
        assertThat(summary.approvedCount()).isEqualTo(3);
        assertThat(summary.inReviewCount()).isEqualTo(5);
        assertThat(summary.declinedCount()).isEqualTo(1);
        assertThat(summary.approvalRate()).isEqualTo(0.75);
        assertThat(summary.approvedVolume()).isEqualByComparingTo("450.00");
        assertThat(summary.feesPaid()).isEqualByComparingTo("15.75");
        assertThat(summary.netPaidOut()).isEqualByComparingTo("434.25");
    }

    @Test
    void summaryForMerchant_hasNullApprovalRateAndZeroMoney_whenNothingIsDecidedOrPaid() {
        when(applicationRepository.totalsByStatusForMerchant(merchantId)).thenReturn(List.of(
                new MerchantStatusTotals(ApplicationStatus.MANUAL_REVIEW, 2L, new BigDecimal("900.00"))));
        when(merchantPayoutRepository.totalsForMerchant(merchantId)).thenReturn(new MerchantPayoutTotals(null, null));

        MerchantSummaryResponse summary = service.summaryForMerchant(merchantId);

        assertThat(summary.totalCheckouts()).isEqualTo(2);
        assertThat(summary.approvalRate()).isNull();
        assertThat(summary.approvedVolume()).isEqualByComparingTo("0");
        assertThat(summary.feesPaid()).isEqualByComparingTo("0");
        assertThat(summary.netPaidOut()).isEqualByComparingTo("0");
    }
```

- [ ] **Step 2: Write the failing integration tests**

Add to `MerchantControllerIntegrationTest` (import `static org.hamcrest.Matchers.closeTo`):

```java
    @Test
    void summaryTotalsMatchTheMerchantsCheckoutsAndPayouts() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("3.50")));
        Merchant other = merchantRepository.save(new Merchant("Merchant B", new BigDecimal("3.50")));
        checkout(merchant.getId(), "100.00");   // approved, fee 3.50
        checkout(merchant.getId(), "200.00");   // approved, fee 7.00
        checkout(merchant.getId(), "600.00");   // manual review
        checkout(merchant.getId(), "1500.00");  // declined
        checkout(other.getId(), "300.00");      // someone else's, must not count

        mockMvc.perform(get("/api/v1/merchants/{id}/summary", merchant.getId())
                        .with(merchantJwt(merchant.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCheckouts").value(4))
                .andExpect(jsonPath("$.approvedCount").value(2))
                .andExpect(jsonPath("$.inReviewCount").value(1))
                .andExpect(jsonPath("$.declinedCount").value(1))
                .andExpect(jsonPath("$.approvalRate", closeTo(0.6667, 0.001)))
                .andExpect(jsonPath("$.approvedVolume").value(300.00))
                .andExpect(jsonPath("$.feesPaid").value(10.50))
                .andExpect(jsonPath("$.netPaidOut").value(289.50));
    }

    @Test
    void summaryForAMerchantWithNoCheckoutsIsZeroedWithNoApprovalRate() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("3.50")));

        mockMvc.perform(get("/api/v1/merchants/{id}/summary", merchant.getId())
                        .with(merchantJwt(merchant.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCheckouts").value(0))
                .andExpect(jsonPath("$.approvalRate").doesNotExist())
                .andExpect(jsonPath("$.feesPaid").value(0))
                .andExpect(jsonPath("$.netPaidOut").value(0));
    }

    @Test
    void merchantIsBlockedFromAnotherMerchantsSummary() throws Exception {
        Merchant merchantA = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("3.50")));
        Merchant merchantB = merchantRepository.save(new Merchant("Merchant B", new BigDecimal("3.50")));

        mockMvc.perform(get("/api/v1/merchants/{id}/summary", merchantB.getId())
                        .with(merchantJwt(merchantA.getId())))
                .andExpect(status().isForbidden());
    }
```

(`doesNotExist()` passes whether Jackson writes `"approvalRate": null` or omits the key.)

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd services/application-service && mvn test -Dtest='CreditApplicationServiceTest,MerchantControllerIntegrationTest'`
Expected: compile FAIL — `cannot find symbol` for `MerchantStatusTotals`, `MerchantPayoutTotals`, `MerchantSummaryResponse`, `summaryForMerchant`.

- [ ] **Step 4: Add the projection records and queries**

`repository/MerchantStatusTotals.java`:

```java
package com.bridgepay.application.repository;

import com.bridgepay.application.domain.ApplicationStatus;

import java.math.BigDecimal;

public record MerchantStatusTotals(ApplicationStatus status, Long count, BigDecimal volume) {
}
```

`repository/MerchantPayoutTotals.java`:

```java
package com.bridgepay.application.repository;

import java.math.BigDecimal;

/** Both sums are null when the merchant has no payouts yet. */
public record MerchantPayoutTotals(BigDecimal gross, BigDecimal fees) {
}
```

In `CreditApplicationRepository` add (imports `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`, `java.util.List`):

```java
    @Query("""
            select new com.bridgepay.application.repository.MerchantStatusTotals(a.status, count(a), sum(a.amount))
            from CreditApplication a
            where a.merchant.id = :merchantId
            group by a.status""")
    List<MerchantStatusTotals> totalsByStatusForMerchant(@Param("merchantId") UUID merchantId);
```

In `MerchantPayoutRepository` add (same two annotation imports):

```java
    @Query("""
            select new com.bridgepay.application.repository.MerchantPayoutTotals(sum(p.amount), sum(p.feeAmount))
            from MerchantPayout p
            where p.merchant.id = :merchantId""")
    MerchantPayoutTotals totalsForMerchant(@Param("merchantId") UUID merchantId);
```

- [ ] **Step 5: Add the DTO**

`dto/MerchantSummaryResponse.java`:

```java
package com.bridgepay.application.dto;

import java.math.BigDecimal;

/**
 * All-time totals for the merchant dashboard tiles. approvalRate is
 * approved / (approved + declined) - in-review applications have no outcome
 * yet - and is null when nothing has been decided.
 */
public record MerchantSummaryResponse(
        long totalCheckouts,
        long approvedCount,
        long inReviewCount,
        long declinedCount,
        Double approvalRate,
        BigDecimal approvedVolume,
        BigDecimal feesPaid,
        BigDecimal netPaidOut
) {
}
```

- [ ] **Step 6: Add the service method**

In `CreditApplicationService` add imports `com.bridgepay.application.dto.MerchantSummaryResponse`, `com.bridgepay.application.repository.MerchantStatusTotals`, `com.bridgepay.application.repository.MerchantPayoutTotals`, and after `listSalesForMerchant`:

```java
    @Transactional(readOnly = true)
    public MerchantSummaryResponse summaryForMerchant(UUID merchantId) {
        long total = 0;
        long approved = 0;
        long inReview = 0;
        long declined = 0;
        BigDecimal approvedVolume = BigDecimal.ZERO;
        for (MerchantStatusTotals totals : applicationRepository.totalsByStatusForMerchant(merchantId)) {
            total += totals.count();
            switch (totals.status()) {
                case APPROVED -> {
                    approved = totals.count();
                    approvedVolume = totals.volume();
                }
                case MANUAL_REVIEW -> inReview = totals.count();
                case DECLINED -> declined = totals.count();
                // ponytail: COMPLETED/DEFAULTED are never set by this service today, so they only count toward
                // the total; fold them into approved count/volume once repayment status flows back here.
                default -> { }
            }
        }
        Double approvalRate = approved + declined == 0 ? null : (double) approved / (approved + declined);

        MerchantPayoutTotals payouts = merchantPayoutRepository.totalsForMerchant(merchantId);
        BigDecimal gross = payouts.gross() != null ? payouts.gross() : BigDecimal.ZERO;
        BigDecimal fees = payouts.fees() != null ? payouts.fees() : BigDecimal.ZERO;

        return new MerchantSummaryResponse(total, approved, inReview, declined, approvalRate,
                approvedVolume, fees, gross.subtract(fees));
    }
```

- [ ] **Step 7: Add the endpoint**

In `MerchantController` (import `com.bridgepay.application.dto.MerchantSummaryResponse`), add after `sales`:

```java
    @GetMapping("/{id}/summary")
    @PreAuthorize("hasRole('MERCHANT')")
    public MerchantSummaryResponse summary(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        requireOwnMerchant(jwt, id);
        return applicationService.summaryForMerchant(id);
    }
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `cd services/application-service && mvn test -Dtest='CreditApplicationServiceTest,MerchantControllerIntegrationTest'`
Expected: all PASS. If Hibernate rejects the constructor expression (e.g. a type mismatch on `count`), the error names the expected constructor signature. Change the record component type to match it (`Long` for `count`, `BigDecimal` for `sum` over a `NUMERIC` column); don't switch to `Object[]`.

- [ ] **Step 9: Run the whole service suite**

Run: `cd services/application-service && mvn clean verify`
Expected: BUILD SUCCESS, 0 failures — every pre-existing test plus the 9 new ones (4 from Task 1, 5 here). Record the real total for PROGRESS.md.

- [ ] **Step 10: Commit**

```bash
git add services/application-service
git commit -m "application-service: add merchant-scoped GET /api/v1/merchants/{id}/summary

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Sales data service and models (main-app)

**Files:**
- Create: `frontend/main-app/src/app/shared/models/merchant-sale.ts`
- Create: `frontend/main-app/src/app/shared/models/merchant-summary.ts`
- Create: `frontend/main-app/src/app/merchant/sales.ts`
- Test: `frontend/main-app/src/app/merchant/sales.spec.ts`

**Interfaces:**
- Consumes: HTTP contracts from Tasks 1–2; existing `Page<T>` (`shared/models/page.ts`), `environment.gatewayBaseUrl`.
- Produces:
  - `interface MerchantSaleResponse { id: string; createdAt: string; amount: number; status: string; installmentCount: number | null; installmentAmount: number | null; decisionAt: string | null; }`
  - `interface MerchantSummaryResponse { totalCheckouts: number; approvedCount: number; inReviewCount: number; declinedCount: number; approvalRate: number | null; approvedVolume: number; feesPaid: number; netPaidOut: number; }`
  - `Sales.list(merchantId: string, status: string, page = 0, size = 20): Observable<Page<MerchantSaleResponse>>`
  - `Sales.summary(merchantId: string): Observable<MerchantSummaryResponse>`

- [ ] **Step 1: Write the failing test**

`merchant/sales.spec.ts`:

```ts
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Sales } from './sales';
import { MerchantSaleResponse } from '../shared/models/merchant-sale';
import { MerchantSummaryResponse } from '../shared/models/merchant-summary';

describe('Sales', () => {
  let service: Sales;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(Sales);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists sales for a merchant with status and page params', () => {
    let result: MerchantSaleResponse[] | undefined;
    service.list('m-1', 'APPROVED', 2).subscribe((page) => (result = page.content));

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/merchants/m-1/sales'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('status')).toBe('APPROVED');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('20');
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 2, size: 20 });

    expect(result).toEqual([]);
  });

  it('fetches the merchant summary', () => {
    let result: MerchantSummaryResponse | undefined;
    service.summary('m-1').subscribe((s) => (result = s));

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/merchants/m-1/summary'));
    expect(req.request.method).toBe('GET');
    const body: MerchantSummaryResponse = {
      totalCheckouts: 0, approvedCount: 0, inReviewCount: 0, declinedCount: 0,
      approvalRate: null, approvedVolume: 0, feesPaid: 0, netPaidOut: 0,
    };
    req.flush(body);

    expect(result).toEqual(body);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend/main-app && npx ng test --watch=false`
Expected: FAIL — cannot resolve `./sales` / `../shared/models/merchant-sale`.

- [ ] **Step 3: Add the models and service**

`shared/models/merchant-sale.ts`:

```ts
export interface MerchantSaleResponse {
  id: string;
  createdAt: string;
  amount: number;
  status: string;
  installmentCount: number | null;
  installmentAmount: number | null;
  decisionAt: string | null;
}
```

`shared/models/merchant-summary.ts`:

```ts
export interface MerchantSummaryResponse {
  totalCheckouts: number;
  approvedCount: number;
  inReviewCount: number;
  declinedCount: number;
  approvalRate: number | null;
  approvedVolume: number;
  feesPaid: number;
  netPaidOut: number;
}
```

`merchant/sales.ts`:

```ts
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { Page } from '../shared/models/page';
import { MerchantSaleResponse } from '../shared/models/merchant-sale';
import { MerchantSummaryResponse } from '../shared/models/merchant-summary';

@Injectable({ providedIn: 'root' })
export class Sales {
  private readonly http = inject(HttpClient);

  list(merchantId: string, status: string, page = 0, size = 20): Observable<Page<MerchantSaleResponse>> {
    return this.http.get<Page<MerchantSaleResponse>>(
      `${environment.gatewayBaseUrl}/api/v1/merchants/${merchantId}/sales`,
      { params: { status, page, size } },
    );
  }

  summary(merchantId: string): Observable<MerchantSummaryResponse> {
    return this.http.get<MerchantSummaryResponse>(
      `${environment.gatewayBaseUrl}/api/v1/merchants/${merchantId}/summary`,
    );
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend/main-app && npx ng test --watch=false`
Expected: all PASS (every pre-existing spec + 2 new). Note the total; later tasks add to it.

- [ ] **Step 5: Commit**

```bash
git add frontend/main-app/src/app/merchant/sales.ts frontend/main-app/src/app/merchant/sales.spec.ts frontend/main-app/src/app/shared/models/merchant-sale.ts frontend/main-app/src/app/shared/models/merchant-summary.ts
git commit -m "main-app: add merchant Sales data service

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: SalesPage component (main-app)

**Files:**
- Create: `frontend/main-app/src/app/merchant/sales-page/sales-page.ts`
- Create: `frontend/main-app/src/app/merchant/sales-page/sales-page.html`
- Create: `frontend/main-app/src/app/merchant/sales-page/sales-page.css` (empty, matching sibling components)
- Test: `frontend/main-app/src/app/merchant/sales-page/sales-page.spec.ts`

**Interfaces:**
- Consumes: `Sales.list(merchantId, status, page)`, `Sales.summary(merchantId)` (Task 3); `Auth.merchantId(): string | null` (existing).
- Produces: standalone component `SalesPage` (selector `app-sales-page`), exported from `merchant/sales-page/sales-page.ts`.

- [ ] **Step 1: Write the failing tests**

`merchant/sales-page/sales-page.spec.ts`:

```ts
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { SalesPage } from './sales-page';
import { Sales } from '../sales';
import { Auth } from '../../core/auth';
import { Page } from '../../shared/models/page';
import { MerchantSaleResponse } from '../../shared/models/merchant-sale';
import { MerchantSummaryResponse } from '../../shared/models/merchant-summary';

const summary: MerchantSummaryResponse = {
  totalCheckouts: 4, approvedCount: 2, inReviewCount: 1, declinedCount: 1,
  approvalRate: 2 / 3, approvedVolume: 300, feesPaid: 10.5, netPaidOut: 289.5,
};

function salesPage(totalPages: number, number = 0): Page<MerchantSaleResponse> {
  return {
    content: [
      {
        id: 's-1', createdAt: '2026-09-20T10:00:00Z', amount: 1500, status: 'DECLINED',
        installmentCount: null, installmentAmount: null, decisionAt: '2026-09-20T10:00:01Z',
      },
      {
        id: 's-2', createdAt: '2026-09-19T10:00:00Z', amount: 100, status: 'APPROVED',
        installmentCount: 4, installmentAmount: 25, decisionAt: '2026-09-19T10:00:01Z',
      },
    ],
    totalElements: 2, totalPages, number, size: 20,
  };
}

function setup(stub: {
  list?: (id: string, status: string, page: number) => Observable<Page<MerchantSaleResponse>>;
  summary?: () => Observable<MerchantSummaryResponse>;
}) {
  TestBed.configureTestingModule({
    imports: [SalesPage],
    providers: [
      {
        provide: Sales,
        useValue: {
          list: stub.list ?? (() => of(salesPage(1))),
          summary: stub.summary ?? (() => of(summary)),
        },
      },
      { provide: Auth, useValue: { merchantId: () => 'm-1' } },
    ],
  });
  const fixture = TestBed.createComponent(SalesPage);
  fixture.detectChanges();
  return fixture;
}

function button(el: HTMLElement, label: string): HTMLButtonElement {
  return Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === label) as HTMLButtonElement;
}

describe('SalesPage', () => {
  it('renders the summary tiles and a row per sale', () => {
    const el = setup({}).nativeElement as HTMLElement;

    expect(el.querySelector('[data-tile="approved-volume"]')?.textContent).toContain('300.00');
    expect(el.querySelector('[data-tile="approval-rate"]')?.textContent).toContain('67%');
    expect(el.querySelector('[data-tile="fees-paid"]')?.textContent).toContain('10.50');
    expect(el.querySelector('[data-tile="net-paid-out"]')?.textContent).toContain('289.50');
    const text = el.textContent ?? '';
    expect(text).toContain('1,500.00');
    expect(text).toContain('declined');
    expect(text).toContain('4 × 25.00');
  });

  it('shows a dash for the approval rate when nothing has been decided', () => {
    const el = setup({ summary: () => of({ ...summary, approvalRate: null }) }).nativeElement as HTMLElement;

    expect(el.querySelector('[data-tile="approval-rate"]')?.textContent).toContain('—');
  });

  it('fetches all statuses on page 0 by default', () => {
    const calls: [string, number][] = [];
    setup({ list: (_id, status, page) => (calls.push([status, page]), of(salesPage(1))) });

    expect(calls).toEqual([['ALL', 0]]);
  });

  it('pages forward with Next and disables Prev on the first page and Next on the last', () => {
    const calls: [string, number][] = [];
    const fixture = setup({
      list: (_id, status, page) => (calls.push([status, page]), of(salesPage(2, page))),
    });
    const el = fixture.nativeElement as HTMLElement;

    expect(button(el, 'Prev').disabled).toBe(true);
    button(el, 'Next').click();
    fixture.detectChanges();

    expect(calls).toEqual([['ALL', 0], ['ALL', 1]]);
    expect(button(el, 'Next').disabled).toBe(true);
    expect(button(el, 'Prev').disabled).toBe(false);
  });

  it('resets to page 0 when the status filter changes', () => {
    const calls: [string, number][] = [];
    const fixture = setup({
      list: (_id, status, page) => (calls.push([status, page]), of(salesPage(3, page))),
    });
    const el = fixture.nativeElement as HTMLElement;

    button(el, 'Next').click();
    fixture.detectChanges();
    button(el, 'Approved').click();
    fixture.detectChanges();

    expect(calls.at(-1)).toEqual(['APPROVED', 0]);
  });

  it('still shows the sales list when the summary fails', () => {
    const el = setup({ summary: () => throwError(() => new Error('500')) }).nativeElement as HTMLElement;
    const text = el.textContent ?? '';

    expect(text).toContain('Could not load your sales summary');
    expect(text).toContain('1,500.00');
  });

  it('still shows the summary tiles when the sales list fails', () => {
    const el = setup({ list: () => throwError(() => new Error('403')) }).nativeElement as HTMLElement;
    const text = el.textContent ?? '';

    expect(text).toContain('Could not load your sales');
    expect(text).not.toContain('No sales yet');
    expect(el.querySelector('[data-tile="approved-volume"]')?.textContent).toContain('300.00');
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend/main-app && npx ng test --watch=false`
Expected: FAIL — cannot resolve `./sales-page`.

- [ ] **Step 3: Implement the component**

`merchant/sales-page/sales-page.ts`:

```ts
import { Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import { catchError, of, switchMap } from 'rxjs';
import { Sales } from '../sales';
import { Auth } from '../../core/auth';

export const SALE_FILTERS = ['ALL', 'APPROVED', 'MANUAL_REVIEW', 'DECLINED'] as const;
export type SaleFilter = (typeof SALE_FILTERS)[number];

const SALE_FILTER_LABELS: Record<SaleFilter, string> = {
  ALL: 'All',
  APPROVED: 'Approved',
  MANUAL_REVIEW: 'In review',
  DECLINED: 'Declined',
};

@Component({
  selector: 'app-sales-page',
  imports: [DatePipe, DecimalPipe, PercentPipe],
  templateUrl: './sales-page.html',
  styleUrl: './sales-page.css',
})
export class SalesPage {
  private readonly sales = inject(Sales);
  private readonly merchantId = inject(Auth).merchantId() ?? '';

  protected readonly saleFilters = SALE_FILTERS;
  protected readonly saleFilterLabels = SALE_FILTER_LABELS;
  protected readonly filter = signal<SaleFilter>('ALL');
  protected readonly page = signal(0);
  protected readonly summaryError = signal(false);
  protected readonly listError = signal(false);

  protected readonly summary = toSignal(
    this.sales.summary(this.merchantId).pipe(
      catchError(() => {
        this.summaryError.set(true);
        return of(null);
      }),
    ),
    { initialValue: null },
  );

  // filter and page change together on a filter click; one computed keeps that to a single fetch.
  private readonly query = computed(() => ({ status: this.filter(), page: this.page() }));

  private readonly result = toSignal(
    toObservable(this.query).pipe(
      switchMap(({ status, page }) =>
        this.sales.list(this.merchantId, status, page).pipe(
          catchError(() => {
            this.listError.set(true);
            return of(null);
          }),
        ),
      ),
    ),
    { initialValue: null },
  );

  protected readonly rows = computed(() => this.result()?.content ?? []);
  protected readonly totalPages = computed(() => this.result()?.totalPages ?? 0);

  protected selectFilter(status: SaleFilter): void {
    this.listError.set(false);
    this.filter.set(status);
    this.page.set(0);
  }

  protected prevPage(): void {
    this.page.update((p) => Math.max(0, p - 1));
  }

  protected nextPage(): void {
    this.page.update((p) => p + 1);
  }
}
```

`merchant/sales-page/sales-page.html`:

```html
<h1 class="text-xl font-medium mb-4">Sales</h1>

@if (summaryError()) {
  <p class="mb-6 text-sm text-status-declined">Could not load your sales summary. Try refreshing.</p>
} @else {
  @if (summary(); as s) {
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-2">
      <div class="border border-hairline rounded p-4" data-tile="approved-volume">
        <div class="text-xs text-ink-muted mb-1">Approved volume</div>
        <div class="font-mono text-lg">{{ s.approvedVolume | number: '1.2-2' }}</div>
      </div>
      <div class="border border-hairline rounded p-4" data-tile="approval-rate">
        <div class="text-xs text-ink-muted mb-1">Approval rate</div>
        <div class="font-mono text-lg">{{ s.approvalRate === null ? '—' : (s.approvalRate | percent: '1.0-0') }}</div>
      </div>
      <div class="border border-hairline rounded p-4" data-tile="fees-paid">
        <div class="text-xs text-ink-muted mb-1">Fees paid</div>
        <div class="font-mono text-lg">{{ s.feesPaid | number: '1.2-2' }}</div>
      </div>
      <div class="border border-hairline rounded p-4" data-tile="net-paid-out">
        <div class="text-xs text-ink-muted mb-1">Net paid out</div>
        <div class="font-mono text-lg">{{ s.netPaidOut | number: '1.2-2' }}</div>
      </div>
    </div>
    <p class="mb-6 text-xs text-ink-muted font-mono">
      {{ s.approvedCount }} approved · {{ s.inReviewCount }} in review · {{ s.declinedCount }} declined
      of {{ s.totalCheckouts }} checkouts
    </p>
  }
}

<div class="flex gap-2 mb-4">
  @for (status of saleFilters; track status) {
    <button
      type="button"
      (click)="selectFilter(status)"
      class="px-3 py-1 text-sm border rounded"
      [class.border-accent]="filter() === status"
      [class.text-accent]="filter() === status"
      [class.border-hairline]="filter() !== status"
      [class.text-ink-muted]="filter() !== status"
    >
      {{ saleFilterLabels[status] }}
    </button>
  }
</div>

<table class="w-full text-sm">
  <thead>
    <tr class="border-b border-hairline text-left text-ink-muted">
      <th class="py-2 font-normal">Date</th>
      <th class="py-2 font-normal text-right">Amount</th>
      <th class="py-2 font-normal">Plan</th>
      <th class="py-2 font-normal">Status</th>
      <th class="py-2 font-normal">Decided</th>
    </tr>
  </thead>
  <tbody>
    @if (listError()) {
      <tr>
        <td colspan="5" class="py-6 text-center text-status-declined">
          Could not load your sales. Try refreshing.
        </td>
      </tr>
    } @else {
      @for (row of rows(); track row.id) {
        <tr class="border-b border-hairline">
          <td class="py-2 font-mono">{{ row.createdAt | date: 'MMM d, y' }}</td>
          <td class="py-2 font-mono text-right">{{ row.amount | number: '1.2-2' }}</td>
          <td class="py-2 font-mono">
            {{ row.installmentCount ? row.installmentCount + ' × ' + (row.installmentAmount | number: '1.2-2') : '—' }}
          </td>
          <td class="py-2">
            @switch (row.status) {
              @case ('APPROVED') {
                <span class="inline-block w-1.5 h-1.5 rounded-full bg-accent mr-1.5"></span>approved
              }
              @case ('MANUAL_REVIEW') {
                <span class="inline-block w-1.5 h-1.5 rounded-full bg-status-review mr-1.5"></span>in review
              }
              @case ('DECLINED') {
                <span class="inline-block w-1.5 h-1.5 rounded-full bg-status-declined mr-1.5"></span>declined
              }
              @default {
                {{ row.status.toLowerCase() }}
              }
            }
          </td>
          <td class="py-2 font-mono">{{ row.decisionAt ? (row.decisionAt | date: 'MMM d, y') : '—' }}</td>
        </tr>
      } @empty {
        <tr>
          <td colspan="5" class="py-6 text-center text-ink-muted">No sales yet.</td>
        </tr>
      }
    }
  </tbody>
</table>

@if (!listError()) {
  <div class="flex items-center justify-between mt-4 text-sm">
    <button
      type="button"
      (click)="prevPage()"
      [disabled]="page() === 0"
      class="px-3 py-1 border border-hairline rounded disabled:opacity-40"
    >Prev</button>
    <span class="text-ink-muted font-mono">Page {{ page() + 1 }} of {{ totalPages() || 1 }}</span>
    <button
      type="button"
      (click)="nextPage()"
      [disabled]="page() + 1 >= totalPages()"
      class="px-3 py-1 border border-hairline rounded disabled:opacity-40"
    >Next</button>
  </div>
}
```

`merchant/sales-page/sales-page.css`: empty file.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend/main-app && npx ng test --watch=false`
Expected: all PASS (previous total + 7 new). If `'4 × 25.00'` doesn't match, check the rendered text for extra whitespace inside the Plan cell. Fix the template to keep the interpolation on one line; don't loosen the assertion.

- [ ] **Step 5: Commit**

```bash
git add frontend/main-app/src/app/merchant/sales-page
git commit -m "main-app: add merchant SalesPage with summary tiles, status filter and pagination

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Routes and sidebar (main-app)

**Files:**
- Modify: `frontend/main-app/src/app/app.routes.ts`
- Modify: `frontend/main-app/src/app/app.html`
- Test: `frontend/main-app/src/app/app.routes.spec.ts` (create)
- Test: `frontend/main-app/src/app/app.spec.ts`

**Interfaces:**
- Consumes: `SalesPage` (Task 4), existing `PayoutLedger`, `merchantGuard`.
- Produces: routes `/merchant` → `SalesPage`, `/merchant/payouts` → `PayoutLedger`; sidebar links "Sales" and "Payouts" for the merchant role.

- [ ] **Step 1: Write the failing tests**

`app.routes.spec.ts`:

```ts
import { routes } from './app.routes';
import { SalesPage } from './merchant/sales-page/sales-page';
import { PayoutLedger } from './merchant/payout-ledger/payout-ledger';
import { merchantGuard } from './core/merchant-guard';

describe('routes', () => {
  it('lands merchants on the sales page and moves payouts under it', () => {
    const merchant = routes.find((r) => r.path === 'merchant');
    const payouts = routes.find((r) => r.path === 'merchant/payouts');

    expect(merchant?.component).toBe(SalesPage);
    expect(merchant?.canActivate).toEqual([merchantGuard]);
    expect(payouts?.component).toBe(PayoutLedger);
    expect(payouts?.canActivate).toEqual([merchantGuard]);
  });
});
```

In `app.spec.ts`, replace the merchant test and extend the ops test:

```ts
  it('shows the Review Queue link for an ops role', () => {
    const fixture = setup(['ops']);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Review Queue');
    expect(text).not.toContain('Payouts');
    expect(text).not.toContain('Sales');
  });

  it('shows the Sales and Payouts links for a merchant role', () => {
    const fixture = setup(['merchant']);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const links = Array.from(el.querySelectorAll('aside a')).map((a) => [a.textContent?.trim(), a.getAttribute('href')]);
    expect(links).toEqual([
      ['Sales', '/merchant'],
      ['Payouts', '/merchant/payouts'],
    ]);
    expect(el.textContent).not.toContain('Review Queue');
  });
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend/main-app && npx ng test --watch=false`
Expected: FAIL — `merchant` route component is `PayoutLedger`, `merchant/payouts` not found; sidebar has only `Payouts` → `/merchant`.

- [ ] **Step 3: Update routes and sidebar**

`app.routes.ts`: add `import { SalesPage } from './merchant/sales-page/sales-page';` and replace the merchant line with:

```ts
  { path: 'merchant', component: SalesPage, canActivate: [merchantGuard] },
  { path: 'merchant/payouts', component: PayoutLedger, canActivate: [merchantGuard] },
```

`app.html`: replace the merchant `@if` block with:

```html
    @if (auth.hasRole('merchant')) {
      <a
        routerLink="/merchant"
        routerLinkActive="text-accent font-medium"
        [routerLinkActiveOptions]="{ exact: true }"
        class="px-2 py-1.5 rounded-sm hover:bg-hairline/40"
      >Sales</a>
      <a
        routerLink="/merchant/payouts"
        routerLinkActive="text-accent font-medium"
        class="px-2 py-1.5 rounded-sm hover:bg-hairline/40"
      >Payouts</a>
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend/main-app && npx ng test --watch=false`
Expected: all PASS (previous total + 1 new route test; the `app.spec.ts` merchant test was replaced, not added).

- [ ] **Step 5: Verify the production build**

Run: `cd frontend/main-app && npx ng build`
Expected: build succeeds with no template type errors.

- [ ] **Step 6: Commit**

```bash
git add frontend/main-app/src/app/app.routes.ts frontend/main-app/src/app/app.routes.spec.ts frontend/main-app/src/app/app.html frontend/main-app/src/app/app.spec.ts
git commit -m "main-app: make Sales the merchant landing page, move Payouts to /merchant/payouts

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: End-to-end smoke test against the real stack, and PROGRESS.md

**Files:**
- Modify: `docs/PROGRESS.md`

**Interfaces:**
- Consumes: everything above; root `docker-compose.yml` (Keycloak on host port 8180, gateway on 8086, `main-app` on 4200); demo user `merchant1`/`merchant1` whose `merchantId` claim is the seeded merchant `00000000-0000-7000-8000-000000000001`.

- [ ] **Step 1: Check what's already running before touching the stack**

Run: `docker compose ps` from the repo root.
If containers are already up from another session, don't stop them. Rebuild only what changed: `docker compose up --build -d application-service main-app`. If nothing is running: `docker compose up --build -d`.

- [ ] **Step 2: Get a real merchant1 token and call both endpoints through the gateway**

```bash
TOKEN=$(curl -s -X POST http://localhost:8180/realms/bridgepay/protocol/openid-connect/token \
  -d grant_type=password -d client_id=main-app -d username=merchant1 -d password=merchant1 \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
M=00000000-0000-7000-8000-000000000001
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8086/api/v1/merchants/$M/summary
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8086/api/v1/merchants/$M/sales?status=ALL"
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  http://localhost:8086/api/v1/merchants/00000000-0000-7000-8000-000000000002/summary
```

Expected: summary JSON with all 8 fields; sales page JSON with top-level `totalPages` and no `applicantId`/`riskScore` keys; the last call prints `403`. Record the actual outputs for PROGRESS.md.

- [ ] **Step 3: Confirm the frontend route is served**

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:4200/merchant/payouts`
Expected: `200` (SPA fallback). A real click-through (tiles, filter, Next/Prev) needs a human in a browser. Say so in PROGRESS.md instead of claiming it.

- [ ] **Step 4: Update PROGRESS.md**

Add a new `- [x] Merchant sales view — ...` entry under `## Done`, after the shopper account view entry. Cover:
- what shipped: both endpoints, `SalesPage` at `/merchant`, Payouts moved to `/merchant/payouts`, the V3 index
- the "no shopper credit data" rule
- test counts from real runs (`mvn clean verify`, `npx ng test --watch=false`)
- the actual Step 2/3 outputs
- what was not verified (browser click-through)
- the `COMPLETED`/`DEFAULTED` `ponytail:` deferral

Also note under the entry that this was the second of the four expansion sub-projects, and that the payout ledger's own pagination is still deferred.

- [ ] **Step 5: Commit**

```bash
git add docs/PROGRESS.md
git commit -m "Record the merchant sales view feature in PROGRESS.md

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 6: Leave the stack as you found it**

If Step 1 found nothing running, `docker compose down` and confirm `docker compose ps -a` is empty. If it was already running, leave it running.
