# Risk Policy Overlay — Design

Third of four "expansion" sub-projects (shopper → merchant → **risk/ML** → platform/ops).

## Problem

Spec §6 promises that on-platform repayment history — the one genuinely real
scoring input — makes a returning shopper's score evolve over time. Today it
doesn't: `FeatureVector` builds `completedPlans`, `defaultedPlans`,
`latePaymentCount`, `onTimeRate`, `requestedAmount` and `hourOfDay`, but the
trained model (`coefficients.json`) only consumes the 10 bureau features, so
those values are fetched and thrown away. Retraining can't fix this: the
Kaggle dataset has no on-platform-history columns.

## Solution

A transparent, rule-based **policy overlay** applied on top of the model's
output, in log-odds units — the same units the existing `scoreFactors`
(`coefficient × scaled value`) are already expressed in, so fired rules
render in the ops review chart alongside bureau factors with no contract
change.

### Flow (Credit Risk Engine only)

`ScoringService.score`: bureau + history fetch → `FeatureVector` →
`ModelScorer.score` (unchanged) → **`PolicyOverlay.apply`** → sigmoid →
0.3/0.7 bands, unless the overlay forces `DECLINE`. The existing fail-safe
catch still wraps the whole pipeline (any failure → `MANUAL_REVIEW`).

- The model logit is derived from the returned probability,
  `ln(p / (1 - p))`, so `ModelScorer`/`ScoreOutcome` are untouched. The
  probability is clamped to `[1e-9, 1 - 1e-9]` first to avoid infinities.
- `riskScore` in the response becomes the **adjusted** probability.
- `scoreFactors` = model factors followed by any fired overlay factors.

### `PolicyOverlay`

A pure, stateless class in the `scoring` package. Input: model logit,
`RepaymentHistory`, bureau `monthlyIncome`, request `amount`. Output: adjusted
logit, list of fired `ScoreFactor`s, `forceDecline` flag. Weights are
constants in the class (marked `ponytail:` — move to a `policy.json` beside
`coefficients.json` if they ever need tuning without a code change).

| Factor key | Fires when | Log-odds contribution |
|---|---|---|
| `priorDefault` | `defaultedPlans > 0` | +3.0, **and forces `DECLINE`** |
| `latePayments` | `latePaymentCount > 0` | +0.6 per late/missed payment, capped at +2.4 |
| `completedPlans` | `completedPlans > 0` | −0.4 per completed plan, capped at −1.2 |
| `amountToIncome` | `amount / monthlyIncome > 0.5`, or `monthlyIncome <= 0` | +1.0 |

A rule that doesn't fire emits no factor — a first-time shopper's breakdown
is exactly today's 10 bureau factors.

**Deliberately excluded:** `merchantCategory` and `hourOfDay` (no evidence
either predicts default; an invented weight would be decoration posing as
risk logic), `onTimeRate` (redundant with `latePayments`).

### `main-app`

`ReviewDetail`'s feature-label map gains labels for the four new keys
(e.g. `priorDefault` → "Prior BridgePay default"). The chart already handles
signed contributions.

### Unchanged

`ScoreResponse`/`ScoreFactor` shape, Application Service's storage of
`scoreFactors`, the 30s Redis score cache (a newly recorded late payment
affects scores within 30s), all other services.

## Testing

- `PolicyOverlayTest` (plain unit): each rule fires/doesn't fire at its
  boundary, both caps, `priorDefault` forces decline, zero/negative income,
  clean history emits no factors and leaves the logit unchanged.
- `ScoringServiceTest`: a model result that would `APPROVE` becomes `DECLINE`
  with a `priorDefault` history; overlay factors are appended after model
  factors; `riskScore` reflects the adjusted logit.
- `review-detail.spec.ts`: new keys render human-readable labels.
- `mvn clean verify` (credit-risk-engine), `npx ng test --watch=false`
  (main-app).

## Manual verification

`repayment_plans` rows are only created via the Paddle-gated Kafka path
(placeholder key → 403), so seed `repayment_plans`/`installments` rows
directly into the shared Postgres for a demo applicant, and call
`/internal/score` before and after seeding to observe the score move and the
new factors appear.

## Out of scope

Model versioning/governance, score monitoring dashboards, retraining with
on-platform features, merchant-category encoding.
