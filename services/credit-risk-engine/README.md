# BridgePay — Credit Risk Engine

Scores checkouts and sets shoppers' spending limits. It loads a scikit-learn pipeline exported to
ONNX plus a `coefficients.json` explainability sidecar, and assembles each feature vector itself
from the Mock Credit Bureau and Repayment Reconciliation (each behind its own circuit breaker).

## Scoring

1. **Model**: `StandardScaler` + `LogisticRegression` on 10 bureau-style features (see
   `scripts/train_model.py`; eval AUC-ROC 0.82). Each feature's log-odds contribution is returned
   as a score factor.
2. **Policy overlay** (`PolicyOverlay`), in the same log-odds units, for on-platform history the
   Kaggle data can't teach: a prior default (+3.0 and a forced decline), late payments (+0.6 each,
   capped), completed plans (−0.4 each, capped), and an order above half the monthly income (+1.0).
3. **Band**: below 0.3 approve, above 0.7 decline, otherwise manual review.

Any failure along the way (bureau, history, model) returns `MANUAL_REVIEW`, never a guess. Scores
are cached in Redis.

## Spending limit

`/internal/credit-limit/{applicantId}` runs the same pipeline without the order amount: a low-risk
band gets `min(income/2, 1500)`, medium `min(income/4, 500)`, high risk or a prior default `0`.
Because a low-risk limit never exceeds half the income, an order at that limit still scores
approve at checkout.

## Model monitoring

`model/baseline.json` holds the eval split's score distribution and default rate per score bin
(built by `scripts/build_monitoring_baseline.py`, tied to the exact `model.onnx` by hash). Ops read it
at `GET /api/v1/model` for the drift and outcomes page; a model/baseline hash mismatch returns `503`.
Rerun the script after any retrain.

## Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/internal/score` | cluster only | Score a checkout |
| GET | `/internal/credit-limit/{applicantId}` | cluster only | Spending limit and band |
| GET | `/api/v1/model` | ops | Model version and training baseline |

`/internal/**` is not on any Ingress or gateway route; network isolation is the control there.
