# BridgePay

[![CI](https://github.com/davidsoldat/bridgepay/actions/workflows/ci.yml/badge.svg)](https://github.com/davidsoldat/bridgepay/actions/workflows/ci.yml)

An instant-credit / Buy-Now-Pay-Later underwriting platform, similar in shape to Klarna or Afterpay. A shopper splits a purchase into four installments at checkout, a trained credit-risk model decides in real time whether to extend credit, the merchant is paid in full immediately, and the platform collects the installments afterward — carrying the default risk itself.

Built as independently deployable Spring Boot microservices with Kafka, a from-scratch trained ML model served via ONNX Runtime, Keycloak auth, and three Angular frontends.

## Live demo

| | |
|---|---|
| **[bridgepay.duckdns.org](https://bridgepay.duckdns.org)** | Start here: what BridgePay is, the demo logins and a guided walkthrough |
| [shop.bridgepay.duckdns.org](https://shop.bridgepay.duckdns.org) | Storefront: check out with "Pay in 4" as a shopper |
| [app.bridgepay.duckdns.org](https://app.bridgepay.duckdns.org) | Main app: ops review and merchant dashboards |

Demo users (password = username): `shopper1`, `ops1`, `merchant1`. A full loop: check out as `shopper1` → review and approve as `ops1` → pay the first installment with Paddle's sandbox card `4242 4242 4242 4242` → see the sale and payout as `merchant1`.

It's a public demo: don't enter real personal details. All data is erased nightly.

## Architecture

```mermaid
flowchart LR
    SF[Storefront<br/>Angular] --> GW
    MA[Main app<br/>Angular] --> GW
    SF & MA -. OIDC / PKCE .-> KC[Keycloak]

    GW[API Gateway] --> AP[Applicant Service]
    GW --> AS[Application Service]
    GW --> RR[Repayment Reconciliation]

    AS -- score --> CRE[Credit Risk Engine<br/>ONNX model]
    CRE --> MCB[Mock Credit Bureau]
    CRE --> RR
    CRE --- R[(Redis)]

    AS -- outbox --> K{{Kafka}}
    K --> RR
    K --> NS[Notifications Service]
    RR -- outbox --> K
    RR --> AP
    RR <--> PD[Paddle]
```

| Component | What it does |
|---|---|
| **Landing** | Public home page: what BridgePay is, a guided demo with the demo logins, and the architecture |
| **API Gateway** | Spring Cloud Gateway: path routing, JWT validation, rate limiting, correlation IDs |
| **Applicant Service** | Shopper signup and identity profile. No Kafka, by design |
| **Application Service** | Checkout flow, decision finalization, ops review, merchant sales/payouts. Publishes decisions through a transactional outbox |
| **Credit Risk Engine** | Scores applications with a logistic-regression model (ONNX), plus a policy overlay for on-platform repayment history. Fails safe to manual review |
| **Mock Credit Bureau** | Stands in for a real bureau: deterministically maps each applicant to a held-out row of the Kaggle *Give Me Some Credit* dataset |
| **Repayment Reconciliation** | Creates installment plans in Paddle, verifies Paddle webhooks, tracks repayments. Failed events land in an ops retry queue |
| **Notifications Service** | Idempotent Kafka consumer for decision and repayment events |
| **Storefront** | Demo merchant shop with the embedded "Pay in 4" checkout and a shopper account page |
| **Main app** | Role-gated ops dashboard (review queue, score explanations, failed events) and merchant dashboard (sales, payouts) |

Every external call (bureau, repayment history, Paddle, applicant lookup) sits behind its own circuit breaker. Any scoring failure routes the application to manual review rather than approving or declining blind.

## Tech stack

Java 21 · Spring Boot 4 · Spring Cloud Gateway · PostgreSQL (schema per service, Flyway) · Kafka (KRaft) · Redis · Keycloak · ONNX Runtime · scikit-learn · Angular 21 (standalone components, signals) · Tailwind · Testcontainers · Docker · GitHub Actions → GHCR (linux/arm64) · k3s

## The model

`services/credit-risk-engine/scripts/train_model.py` trains a `StandardScaler` + `LogisticRegression` on 10 bureau-style features from the Kaggle dataset, excluding the exact rows the mock bureau serves, and exports to ONNX. Eval AUC-ROC is 0.82. Each decision stores per-feature log-odds contributions, which the ops review screen renders as an explanation chart.

## Deployment

Runs on a single ARM64 OCI instance under k3s, with Let's Encrypt TLS. Every green push to `main` is tested, built into `linux/arm64` images on GHCR and deployed by GitHub Actions. Manifests and the runbook are in [`infrastructure/k8s`](infrastructure/k8s).

## Repository layout

```
services/        7 Spring Boot services, one Maven project each
frontend/        landing, main-app and storefront (Angular)
infrastructure/  Keycloak realm + login theme, Postgres init, Kubernetes manifests
docs/            platform spec: architecture, schemas, Kafka and API contracts, ML plan
data/            training data location (dataset not committed)
```

The full design is in [`docs/bridgepay-platform-spec.md`](docs/bridgepay-platform-spec.md).
