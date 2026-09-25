# Risk Policy Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make on-platform repayment history and amount-to-income actually move credit decisions via a transparent log-odds policy overlay on top of the ONNX model.

**Architecture:** A pure `PolicyOverlay` class in `credit-risk-engine`'s `scoring` package adjusts the model's logit and emits extra `ScoreFactor`s; `ScoringService` applies it between model scoring and banding, with a forced-`DECLINE` hard stop on prior defaults. `main-app` gets labels for the new factor keys. No contract, schema, or topic changes.

**Tech Stack:** Java 21, Spring Boot 4.1.1, JUnit 5 + AssertJ + Mockito; Angular 21 + Vitest (`npx ng test`).

**Spec:** `docs/superpowers/specs/2026-09-25-risk-policy-overlay-design.md`

## Global Constraints

- Weights (log-odds): `priorDefault` +3.0 and forces DECLINE when `defaultedPlans > 0`; `latePayments` +0.6 each capped at +2.4; `completedPlans` −0.4 each capped at −1.2; `amountToIncome` +1.0 when `amount / monthlyIncome > 0.5` or `monthlyIncome <= 0`.
- A rule that doesn't fire emits no factor.
- Model logit derived as `ln(p/(1-p))` with `p` clamped to `[1e-9, 1 - 1e-9]`.
- `riskScore` = adjusted probability; `scoreFactors` = model factors then overlay factors.
- Bands unchanged: `< 0.3` APPROVE, `< 0.7` MANUAL_REVIEW, else DECLINE.
- Any failure anywhere still → `ScoreResponse.manualReviewFallback()`.
- `ScoreResponse`/`ScoreFactor` shapes unchanged.

## Review Focus

- A shopper with a prior default whose model probability is tiny (e.g. 0.001) must still be DECLINED — the +3.0 alone wouldn't get there; the force flag must. (Task 2 test.)
- A first-time shopper (all-zero history, modest amount) must get exactly the model's probability back — no floating-point drift from a logit→sigmoid round trip. (Existing parameterized test in Task 2 pins this with `isEqualTo`.)
- Bureau `monthlyIncome` of 0 (present in the Kaggle data) must not throw or divide by zero — the rule fires. (Task 1 test.)
- Amount exactly half of income must NOT fire `amountToIncome` (strict `>`). (Task 1 test.)
- Very large late-payment/completed-plan counts must hit the caps, not scale unbounded. (Task 1 tests.)

---

### Task 1: `PolicyOverlay`

**Files:**
- Create: `services/credit-risk-engine/src/main/java/com/bridgepay/creditrisk/scoring/PolicyOverlay.java`
- Test: `services/credit-risk-engine/src/test/java/com/bridgepay/creditrisk/scoring/PolicyOverlayTest.java`

**Interfaces:**
- Consumes: `RepaymentHistory(int completedPlans, int defaultedPlans, int latePaymentCount, double onTimeRate)`, `ScoreFactor(String feature, double contribution)`.
- Produces: `PolicyOverlay.apply(double logit, RepaymentHistory history, double monthlyIncome, BigDecimal amount)` → `PolicyOverlay.Result(double logit, List<ScoreFactor> factors, boolean forceDecline)` (package-private).

- [ ] **Step 1: Write the failing test**

```java
package com.bridgepay.creditrisk.scoring;

import com.bridgepay.creditrisk.client.RepaymentHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PolicyOverlayTest {

    private static final RepaymentHistory CLEAN = new RepaymentHistory(0, 0, 0, 1.0);
    private static final BigDecimal SMALL = new BigDecimal("100.00");

    @Test
    void cleanHistoryAndModestAmount_changesNothing() {
        PolicyOverlay.Result r = PolicyOverlay.apply(-1.5, CLEAN, 5000.0, SMALL);

        assertThat(r.logit()).isEqualTo(-1.5);
        assertThat(r.factors()).isEmpty();
        assertThat(r.forceDecline()).isFalse();
    }

    @Test
    void priorDefault_addsThreeAndForcesDecline() {
        PolicyOverlay.Result r = PolicyOverlay.apply(-1.5, new RepaymentHistory(0, 1, 0, 1.0), 5000.0, SMALL);

        assertThat(r.logit()).isCloseTo(1.5, within(1e-9));
        assertThat(r.factors()).containsExactly(new ScoreFactor("priorDefault", 3.0));
        assertThat(r.forceDecline()).isTrue();
    }

    @Test
    void latePayments_addPointSixEach() {
        PolicyOverlay.Result r = PolicyOverlay.apply(0.0, new RepaymentHistory(0, 0, 2, 0.5), 5000.0, SMALL);

        assertThat(r.factors()).hasSize(1);
        assertThat(r.factors().getFirst().feature()).isEqualTo("latePayments");
        assertThat(r.factors().getFirst().contribution()).isCloseTo(1.2, within(1e-9));
        assertThat(r.logit()).isCloseTo(1.2, within(1e-9));
        assertThat(r.forceDecline()).isFalse();
    }

    @Test
    void latePayments_capAtTwoPointFour() {
        PolicyOverlay.Result r = PolicyOverlay.apply(0.0, new RepaymentHistory(0, 0, 50, 0.1), 5000.0, SMALL);

        assertThat(r.factors().getFirst().contribution()).isCloseTo(2.4, within(1e-9));
    }

    @Test
    void completedPlans_subtractPointFourEach() {
        PolicyOverlay.Result r = PolicyOverlay.apply(0.0, new RepaymentHistory(2, 0, 0, 1.0), 5000.0, SMALL);

        assertThat(r.factors()).hasSize(1);
        assertThat(r.factors().getFirst().feature()).isEqualTo("completedPlans");
        assertThat(r.factors().getFirst().contribution()).isCloseTo(-0.8, within(1e-9));
    }

    @Test
    void completedPlans_capAtMinusOnePointTwo() {
        PolicyOverlay.Result r = PolicyOverlay.apply(0.0, new RepaymentHistory(40, 0, 0, 1.0), 5000.0, SMALL);

        assertThat(r.factors().getFirst().contribution()).isCloseTo(-1.2, within(1e-9));
    }

    @Test
    void amountToIncome_firesAboveHalfOfMonthlyIncome() {
        PolicyOverlay.Result r = PolicyOverlay.apply(0.0, CLEAN, 1000.0, new BigDecimal("500.01"));

        assertThat(r.factors()).containsExactly(new ScoreFactor("amountToIncome", 1.0));
        assertThat(r.logit()).isEqualTo(1.0);
    }

    @Test
    void amountToIncome_doesNotFireAtExactlyHalf() {
        PolicyOverlay.Result r = PolicyOverlay.apply(0.0, CLEAN, 1000.0, new BigDecimal("500.00"));

        assertThat(r.factors()).isEmpty();
    }

    @Test
    void amountToIncome_firesWhenIncomeIsZeroOrNegative() {
        assertThat(PolicyOverlay.apply(0.0, CLEAN, 0.0, SMALL).factors())
                .containsExactly(new ScoreFactor("amountToIncome", 1.0));
        assertThat(PolicyOverlay.apply(0.0, CLEAN, -10.0, SMALL).factors())
                .containsExactly(new ScoreFactor("amountToIncome", 1.0));
    }

    @Test
    void multipleRules_sumAndEmitInFixedOrder() {
        PolicyOverlay.Result r = PolicyOverlay.apply(0.0, new RepaymentHistory(1, 1, 1, 0.5), 100.0, SMALL);

        assertThat(r.factors()).extracting(ScoreFactor::feature)
                .containsExactly("priorDefault", "latePayments", "completedPlans", "amountToIncome");
        assertThat(r.logit()).isCloseTo(3.0 + 0.6 - 0.4 + 1.0, within(1e-9));
        assertThat(r.forceDecline()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/credit-risk-engine && mvn test -Dtest=PolicyOverlayTest`
Expected: compilation FAIL, `cannot find symbol: class PolicyOverlay`.

- [ ] **Step 3: Write minimal implementation**

```java
package com.bridgepay.creditrisk.scoring;

import com.bridgepay.creditrisk.client.RepaymentHistory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Rule-based adjustments layered on top of the model's logit, in the same
 * log-odds units as the model's own scoreFactors - so fired rules render in
 * the ops review chart next to the bureau factors. Exists because the
 * trained model only knows the 10 bureau features; on-platform repayment
 * history (spec section 6's one genuinely real input) can't be trained on
 * from the Kaggle data. See docs/superpowers/specs/2026-09-25-risk-policy-overlay-design.md.
 * <p>
 * A rule that doesn't fire emits no factor.
 */
final class PolicyOverlay {

    // ponytail: hand-set weights as constants; move to a policy.json beside
    // coefficients.json if they ever need tuning without a code change.
    private static final double PRIOR_DEFAULT = 3.0;
    private static final double PER_LATE_PAYMENT = 0.6;
    private static final double LATE_PAYMENTS_CAP = 2.4;
    private static final double PER_COMPLETED_PLAN = -0.4;
    private static final double COMPLETED_PLANS_CAP = -1.2;
    private static final double AMOUNT_TO_INCOME = 1.0;
    private static final double AMOUNT_TO_INCOME_THRESHOLD = 0.5;

    record Result(double logit, List<ScoreFactor> factors, boolean forceDecline) {
    }

    private PolicyOverlay() {
    }

    static Result apply(double logit, RepaymentHistory history, double monthlyIncome, BigDecimal amount) {
        List<ScoreFactor> factors = new ArrayList<>();
        if (history.defaultedPlans() > 0) {
            factors.add(new ScoreFactor("priorDefault", PRIOR_DEFAULT));
        }
        if (history.latePaymentCount() > 0) {
            factors.add(new ScoreFactor("latePayments",
                    Math.min(LATE_PAYMENTS_CAP, PER_LATE_PAYMENT * history.latePaymentCount())));
        }
        if (history.completedPlans() > 0) {
            factors.add(new ScoreFactor("completedPlans",
                    Math.max(COMPLETED_PLANS_CAP, PER_COMPLETED_PLAN * history.completedPlans())));
        }
        if (monthlyIncome <= 0 || amount.doubleValue() / monthlyIncome > AMOUNT_TO_INCOME_THRESHOLD) {
            factors.add(new ScoreFactor("amountToIncome", AMOUNT_TO_INCOME));
        }
        double adjusted = logit + factors.stream().mapToDouble(ScoreFactor::contribution).sum();
        return new Result(adjusted, List.copyOf(factors), history.defaultedPlans() > 0);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd services/credit-risk-engine && mvn test -Dtest=PolicyOverlayTest`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add services/credit-risk-engine/src/main/java/com/bridgepay/creditrisk/scoring/PolicyOverlay.java services/credit-risk-engine/src/test/java/com/bridgepay/creditrisk/scoring/PolicyOverlayTest.java
git commit -m "credit-risk-engine: add PolicyOverlay (repayment history + amount-to-income log-odds rules)"
```

---

### Task 2: Wire `PolicyOverlay` into `ScoringService`

**Files:**
- Modify: `services/credit-risk-engine/src/main/java/com/bridgepay/creditrisk/scoring/ScoringService.java` (the `score` and `toResponse` methods)
- Test: `services/credit-risk-engine/src/test/java/com/bridgepay/creditrisk/scoring/ScoringServiceTest.java`

**Interfaces:**
- Consumes: `PolicyOverlay.apply(double, RepaymentHistory, double, BigDecimal)` → `PolicyOverlay.Result(double logit, List<ScoreFactor> factors, boolean forceDecline)` from Task 1.
- Produces: no new public API; `ScoreResponse` shape unchanged.

- [ ] **Step 1: Write the failing tests** — append to `ScoringServiceTest`:

```java
    @Test
    void score_priorDefaultForcesDecline_evenWhenTheModelWouldApprove() {
        when(bureauClient.fetchProfile(any())).thenReturn(sampleProfile());
        when(repaymentHistoryClient.fetchHistory(any())).thenReturn(new RepaymentHistory(0, 1, 0, 1.0));
        when(modelScorer.score(any())).thenReturn(new ScoreOutcome(0.001, List.of(new ScoreFactor("age", -0.1))));

        ScoreResponse response = scoringService().score(sampleRequest());

        assertThat(response.decision()).isEqualTo(ScoreDecision.DECLINE);
        assertThat(response.scoreFactors())
                .containsExactly(new ScoreFactor("age", -0.1), new ScoreFactor("priorDefault", 3.0));
    }

    @Test
    void score_overlayAdjustsTheRiskScoreAndBand() {
        // model p = 0.2 (APPROVE); 2 late payments add +1.2 log-odds -> sigmoid(ln(0.25) + 1.2) ~= 0.4535
        when(bureauClient.fetchProfile(any())).thenReturn(sampleProfile());
        when(repaymentHistoryClient.fetchHistory(any())).thenReturn(new RepaymentHistory(0, 0, 2, 0.5));
        when(modelScorer.score(any())).thenReturn(new ScoreOutcome(0.2, List.of()));

        ScoreResponse response = scoringService().score(sampleRequest());

        assertThat(response.riskScore()).isCloseTo(0.4535, org.assertj.core.api.Assertions.within(1e-3));
        assertThat(response.decision()).isEqualTo(ScoreDecision.MANUAL_REVIEW);
        assertThat(response.scoreFactors()).extracting(ScoreFactor::feature).containsExactly("latePayments");
    }

    @Test
    void score_handlesAModelProbabilityOfExactlyOne_withoutProducingNaN() {
        when(bureauClient.fetchProfile(any())).thenReturn(sampleProfile());
        when(repaymentHistoryClient.fetchHistory(any())).thenReturn(new RepaymentHistory(3, 0, 0, 1.0));
        when(modelScorer.score(any())).thenReturn(new ScoreOutcome(1.0, List.of()));

        ScoreResponse response = scoringService().score(sampleRequest());

        assertThat(response.riskScore()).isBetween(0.99, 1.0);
        assertThat(response.decision()).isEqualTo(ScoreDecision.DECLINE);
    }
```

The existing `score_mapsProbabilityToTheCorrectDecisionBand` test (clean history, `sampleProfile()` income 5000, amount 199.99 → no rule fires) must keep passing unchanged — it asserts `riskScore` `isEqualTo(probability)` exactly, pinning that a no-op overlay returns the model probability with no round-trip drift.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd services/credit-risk-engine && mvn test -Dtest=ScoringServiceTest`
Expected: the 3 new tests FAIL (decision APPROVE instead of DECLINE / missing overlay factors / riskScore 0.2); existing tests pass.

- [ ] **Step 3: Implement** — in `ScoringService`, replace the body of the `try` block and `toResponse`:

```java
        try {
            BureauProfile profile = bureauClient.fetchProfile(request.applicantId());
            RepaymentHistory history = repaymentHistoryClient.fetchHistory(request.applicantId());
            Map<String, Double> features = FeatureVector.from(profile, history, request);
            ScoreOutcome outcome = modelScorer.score(features);
            PolicyOverlay.Result overlay = PolicyOverlay.apply(
                    logit(outcome.probability()), history, profile.monthlyIncome(), request.amount());
            return toResponse(outcome, overlay);
        } catch (Exception ex) {
```

```java
    private ScoreResponse toResponse(ScoreOutcome outcome, PolicyOverlay.Result overlay) {
        // No rule fired -> return the model's probability untouched, avoiding logit/sigmoid round-trip drift.
        double probability = overlay.factors().isEmpty() ? outcome.probability() : sigmoid(overlay.logit());
        ScoreDecision decision = overlay.forceDecline() ? ScoreDecision.DECLINE : decisionFor(probability);
        List<ScoreFactor> factors = new ArrayList<>(outcome.factors());
        factors.addAll(overlay.factors());
        return new ScoreResponse(probability, decision, List.copyOf(factors));
    }

    private static double logit(double probability) {
        double p = Math.clamp(probability, 1e-9, 1 - 1e-9);
        return Math.log(p / (1 - p));
    }

    private static double sigmoid(double logit) {
        return 1 / (1 + Math.exp(-logit));
    }
```

Add imports `java.util.ArrayList` and `java.util.List`. Update the class Javadoc's first paragraph to mention the step: "…scores it, then applies PolicyOverlay's rule-based log-odds adjustments (on-platform repayment history, amount-to-income)."

- [ ] **Step 4: Run the full service build**

Run: `cd services/credit-risk-engine && mvn clean verify`
Expected: BUILD SUCCESS, 18 existing + 13 new = 31 tests green (Docker must be running for the Testcontainers-Redis test).

- [ ] **Step 5: Commit**

```bash
git add services/credit-risk-engine/src/main/java/com/bridgepay/creditrisk/scoring/ScoringService.java services/credit-risk-engine/src/test/java/com/bridgepay/creditrisk/scoring/ScoringServiceTest.java
git commit -m "credit-risk-engine: apply PolicyOverlay between model scoring and decision banding"
```

---

### Task 3: `main-app` labels for overlay factors

**Files:**
- Modify: `frontend/main-app/src/app/ops/review-detail/review-detail.ts` (`FEATURE_LABELS`)
- Test: `frontend/main-app/src/app/ops/review-detail/review-detail.spec.ts`

**Interfaces:**
- Consumes: factor keys `priorDefault`, `latePayments`, `completedPlans`, `amountToIncome` from Task 1.

- [ ] **Step 1: Write the failing test** — append inside the `describe`:

```ts
  it('labels policy-overlay factors', () => {
    const app: ApplicationResponse = {
      ...baseApp,
      scoreFactors: [
        { feature: 'priorDefault', contribution: 3 },
        { feature: 'latePayments', contribution: 1.2 },
        { feature: 'completedPlans', contribution: -0.4 },
        { feature: 'amountToIncome', contribution: 1 },
      ],
    };

    TestBed.configureTestingModule({
      imports: [ReviewDetail],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: app.applicationId })) } },
        { provide: Applications, useValue: { getApplication: () => of(app), reviewDecision: () => of(app) } },
      ],
    });

    const fixture = TestBed.createComponent(ReviewDetail);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Prior BridgePay default');
    expect(text).toContain('Late BridgePay payments');
    expect(text).toContain('Completed BridgePay plans');
    expect(text).toContain('Amount vs. monthly income');
  });
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend/main-app && npx ng test --watch=false`
Expected: the new test FAILS (raw keys rendered), others pass.

- [ ] **Step 3: Implement** — update the comment and add four entries to `FEATURE_LABELS`:

```ts
// The model's 10 bureau-shaped feature keys (services/credit-risk-engine's
// coefficients.json) plus PolicyOverlay's rule keys - unknown keys fall back
// to the raw name as-is.
const FEATURE_LABELS: Record<string, string> = {
  // ...existing 10 entries unchanged...
  priorDefault: 'Prior BridgePay default',
  latePayments: 'Late BridgePay payments',
  completedPlans: 'Completed BridgePay plans',
  amountToIncome: 'Amount vs. monthly income',
};
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd frontend/main-app && npx ng test --watch=false`
Expected: all green (41 + 1 = 42).

- [ ] **Step 5: Commit**

```bash
git add frontend/main-app/src/app/ops/review-detail/review-detail.ts frontend/main-app/src/app/ops/review-detail/review-detail.spec.ts
git commit -m "main-app: label policy-overlay score factors in review detail"
```

---

### Task 4: Manual verification + PROGRESS.md

**Files:**
- Modify: `docs/PROGRESS.md` (add a Done entry after "Merchant sales view")

- [ ] **Step 1: Check what's running** — `docker compose ps` from repo root. Don't stop anything already running.

- [ ] **Step 2: Bring up the minimum stack** — `docker compose up --build -d postgres redis mock-credit-bureau repayment-reconciliation-service credit-risk-engine` (repayment-reconciliation also needs kafka; let compose `depends_on` pull it in).

- [ ] **Step 3: Score before seeding** — pick a fixed applicant id `A=0199aaaa-0000-7000-8000-000000000001`:

```bash
curl -s -X POST http://localhost:<credit-risk-engine port>/internal/score -H "Content-Type: application/json" \
  -d '{"applicantId":"'$A'","amount":100.00,"merchantCategory":"general","requestedAt":"2026-09-25T12:00:00Z"}'
```

Record `riskScore`/`decision`/factor keys (expect only the 10 bureau factors, unless income makes `amountToIncome` fire). Look up the real port in the root `docker-compose.yml`.

- [ ] **Step 4: Seed history** — inspect the real columns first (`\d repayment.repayment_plans`, `\d repayment.installments` via `docker compose exec postgres psql -U bridgepay -d bridgepay`), then insert one `DEFAULTED` plan for `A` with required columns filled. Wait 30s (score cache TTL), rerun Step 3's curl. Expect `decision":"DECLINE"` and a `priorDefault` factor with contribution `3.0`.

- [ ] **Step 5: Clean up** — delete the seeded rows; stop only containers this task started.

- [ ] **Step 6: Record in PROGRESS.md** — add a `[x] Risk policy overlay` Done entry: what shipped, test counts from Tasks 2/3, the before/after curl numbers from Steps 3–4, and "not verified: no browser click-through of the new labels". Commit:

```bash
git add docs/PROGRESS.md
git commit -m "Record the risk policy overlay feature in PROGRESS.md"
```
