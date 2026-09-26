# BridgePay — Instant Credit / BNPL Underwriting Platform

Inspired by a microservices fintech fraud-detection reference project, reworked around a different problem: real-time credit underwriting instead of payment fraud.

## 1. Overview
A Buy-Now-Pay-Later platform, similar in shape to Klarna/Affirm/Afterpay: shoppers split a purchase into installments at checkout, the platform decides in real time whether to extend credit, pays the merchant in full immediately, and collects the installments from the shopper afterward — carrying the default risk itself.

## 2. Users & Problem Solved
- **Merchants** — want higher conversion/order value at checkout without taking on shopper credit risk themselves.
- **Shoppers** — want an instant "pay in 4" decision instead of a formal loan application.
- **The platform** — makes money on merchant fees, and carries the risk: approve too conservatively and merchants lose sales; approve too loosely and defaults eat margin. This tradeoff is the real engineering/product problem the risk engine exists to solve.

## 3. Positioning
Portfolio piece and potential product. Deliberately fills a gap the other two portfolio projects don't cover: true independently-deployable microservices, Kubernetes, and a from-scratch trained ML model serving pipeline (Sentra is RAG/LLM-agent based; TruckNest is a Spring Modulith monolith).

## 4. High-Level Architecture

**Clients**
- Mock merchant storefront — a minimal standalone Angular page simulating an external merchant's checkout, the "client" hitting the API gateway
- Main app (Angular/TypeScript) — merchant dashboard (approved sales, payout ledger status) and ops/review dashboard (applications in the "review" band, with score breakdown for explainability) as role-gated views in one app rather than separate frontends

**API Gateway** — routing, rate limiting, security validation (same role as the reference project).

**Services** (schema-per-service on a single shared Postgres instance — see note below):

| Service | Responsibility | Data store |
|---|---|---|
| Applicant Service | One-time shopper signup: identity (name, DOB, contact) + payment method only — no financial details collected from the user | Postgres (own schema) |
| Application Service | Loan/BNPL application lifecycle, checkout requests | Postgres (own schema) |
| Credit Risk Engine | Standalone Spring app, loads the trained ONNX model, exposes a REST scoring endpoint | Redis (score cache) |
| Mock Credit Bureau Service | Stubbed external dependency — returns a synthetic, per-user-consistent credit profile (seeded off user ID) | — |
| Notifications Service | Approve/review/decline + repayment alerts | Postgres (own schema, idempotency guard only) — Kafka consumer |
| Repayment Reconciliation Service | Tracks scheduled vs. received installments; integrates with Paddle | Postgres (own schema) |

**On the database decision:** one shared Postgres server, but each service gets its own schema, its own Flyway migration path, and never queries another service's schema directly. Keycloak gets a schema on the same server too. This preserves the actual point of "database per service" (independent data ownership, no cross-service coupling) while cutting down on containers/persistent volumes to manage on a single-node k3s box — separate Postgres containers per service aren't the resource bottleneck here (Kafka and the JVM services are), so splitting further wouldn't save much, just add operational overhead.

All services communicate over **Kafka** for events, except the credit-score check itself, which is a **synchronous REST call** in the application path — mirrors the original project's "most important decision": some checks can't wait on an async event.

## 5. Money & Data Flow
1. Shopper selects BNPL at checkout for amount X.
2. Application Service submits the request; Credit Risk Engine scores it synchronously using the applicant's stored identity (collected once at signup, never re-entered at checkout) plus a synthetic bureau profile (pulled invisibly from the Mock Credit Bureau Service) plus checkout context (amount, merchant category, time of day, prior on-platform repayment history if returning shopper). The shopper sees no financial questions at checkout at all — just the purchase amount and a "Pay in 4" confirmation, matching how real BNPL checkouts behave.
3. Score bands: **0.0–0.29 approve**, **0.3–0.69 manual review**, **0.7–1.0 decline** — same shape as the reference project's fraud thresholds.
4. If approved: merchant is paid immediately — as an **internal ledger entry**, not a real third-party payout. (Real marketplace payout rails — Stripe Connect, Adyen for Platforms — are gated behind business verification everywhere, so this is simulated on purpose, same pattern as the mocked bureau call.)
5. Shopper now owes the platform, split into installments (e.g. 4 payments over 6 weeks).
6. Repayment Reconciliation Service collects each installment via **Paddle** (modeled as a subscription with a weekly billing cycle; the service itself tracks progress and calls Paddle's cancel-subscription endpoint once the final installment clears — Paddle has no native "N cycles then stop" concept, so that logic lives on our side).
7. Late/missed payments feed back into that shopper's on-platform repayment history, used in future scoring.

## 6. Credit Risk Engine (Core Component)
- Standalone Spring Boot service, separate deployable
- Loads a pre-trained model exported to **ONNX**, exposes a REST scoring endpoint
- Training side: **Python + scikit-learn/XGBoost**, trained on a public dataset shaped like real credit applications — e.g. Kaggle's **Give Me Some Credit** (income, revolving utilization, existing credit lines, past-due history, dependents, age) — with `skl2onnx` for export
- Checkout-time features layered on top of the dataset-shaped profile: requested amount, merchant category, time of day, returning-shopper repayment history
- All financial fields (income, utilization, existing debt, etc.) come from the mocked bureau pull, never typed in by the shopper — the only thing the shopper ever provides directly is identity info at signup
- Evaluated with precision/recall/AUC rather than raw accuracy, since default is the minority class

### Mock Credit Bureau Mechanics
- Static, in-memory pool of realistic profiles: a slice of the "Give Me Some Credit" dataset, held out separately from the rows actually used to train the model
- New applicant → hash their internal ID → deterministically index into the pool → same applicant always maps to the same row (no true randomness, no per-request re-roll)
- Full rows are reused as-is rather than randomizing individual fields, to keep correlated attributes (income, debt ratio, utilization, credit lines) internally consistent
- Stateless — no separate datastore needed, it's a pure function of applicant ID
- On-platform repayment history is the one genuinely real (non-mocked) input, tracked by the Repayment Reconciliation Service, so returning shoppers' scores actually evolve over time

## 7. Tech Stack
**Frontend**
- Angular (standalone components + signals), TypeScript — chosen deliberately to diversify the stack, since Sentra and TruckNest are both Next.js
- Auth via Keycloak + OIDC (reusing the identity provider already proven in TruckNest), rather than a Next.js-specific BFF pattern

**Backend**
- Java 21, Spring Boot, Spring Cloud Gateway, Spring Security

**Machine Learning**
- Python, scikit-learn/XGBoost, skl2onnx
- ONNX Runtime (serving, inside the Credit Risk Engine)

**Messaging & Data**
- Apache Kafka
- PostgreSQL (single shared instance, schema-per-service)
- Redis (score cache)

**Payments**
- Paddle — shopper installment collection only
- Internal ledger — merchant payout (no real processor)

**Infrastructure**
- Docker
- Kubernetes — kept basic: plain manifests (Deployments/Services/ConfigMaps/Ingress), run locally via kind or minikube; no Helm, no service mesh, no multi-cluster
- GitHub Actions for CI/CD
- Terraform skipped for v1 — worth adding once the app is stable and deployed, as a separate later milestone

## 8. Database Schemas
All primary keys are UUIDv7 (time-ordered), not random UUIDv4 — same decentralized-generation benefit, but far better Postgres index locality since inserts stay roughly sequential instead of fragmenting a B-tree.

**Applicant schema**
| Table | Key columns |
|---|---|
| `applicants` | `id`, `keycloak_subject_id`, `first_name`, `last_name`, `date_of_birth`, `email`, `phone`, `paddle_customer_id` (nullable), `created_at`, `updated_at` |

**Application schema**
| Table | Key columns |
|---|---|
| `merchants` | `id`, `name`, `fee_rate_pct`, `created_at` — seeded demo rows, not a full onboarding system |
| `applications` | `id`, `applicant_id` (UUID, app-level reference only — see rule below), `merchant_id` (real FK), `amount`, `status` (PENDING/APPROVED/MANUAL_REVIEW/DECLINED/COMPLETED/DEFAULTED), `risk_score`, `score_factors` (jsonb — explainability breakdown for the ops dashboard), `decision_at`, `installment_count`, `installment_amount`, `version` (optimistic locking), `created_at`, `updated_at` |
| `merchant_payouts` | `id`, `application_id` (FK), `merchant_id` (FK), `amount`, `fee_amount`, `status` (PENDING/PAID), `paid_at` — the internal ledger entry |
| `idempotency_keys` | `id`, `idempotency_key` (unique), `applicant_id`, `response_snapshot` (jsonb, cached response to replay), `created_at`, `expires_at` — guards the checkout/apply endpoint against retry-created duplicate applications |

**Repayment schema**
| Table | Key columns |
|---|---|
| `repayment_plans` | `id`, `application_id` (reference only), `applicant_id` (reference only), `paddle_subscription_id`, `total_amount`, `installment_count`, `status` (ACTIVE/COMPLETED/DEFAULTED/CANCELLED) |
| `installments` | `id`, `repayment_plan_id` (FK), `sequence_number`, `due_date`, `amount`, `status` (SCHEDULED/PAID/LATE/MISSED), `paddle_transaction_id`, `paid_at` |

**Notifications schema**
| Table | Key columns |
|---|---|
| `notification_log` | `event_id` (unique), `applicant_id`, `type`, `sent_at` — idempotency guard against Kafka's at-least-once redelivery double-sending an alert |

**Cross-schema rules:**
1. No database-level foreign keys across schemas — an `applicant_id` inside the Application schema is a plain UUID, validated at the application layer, not enforced by Postgres. A service can't have referential integrity into data it doesn't own without coupling to that service's migrations.
2. Each service connects with its own Postgres role, scoped to only its own schema — this is what makes "database per service" actually true on shared infrastructure, enforced by permissions rather than convention.

## 9. Cross-Cutting Best Practices
**Data layer**
- UUIDv7 primary keys (above); `@Version` optimistic locking on `applications`; auditing columns via Spring Data JPA `@CreatedDate`/`@LastModifiedDate` (same pattern as Sentra/TruckNest)
- Indexes matched to real query patterns: `applications(applicant_id)`, `applications(status)`, `installments(due_date, status)`
- Small, explicitly-sized HikariCP pools per service rather than framework defaults, given the shared instance's resource budget

**Security**
- Bean Validation (`@Valid` + constraints) on every inbound DTO
- `@PreAuthorize` role checks (shopper/merchant/ops) at the method level, layered on top of gateway-level JWT validation
- Kubernetes Secrets for credentials (DB passwords, Paddle keys) — never ConfigMaps or baked into images
- TLS terminated at the ingress

**Resilience & performance**
- Resilience4j circuit breaker + retry around the two externally-fallible calls: Application → Credit Risk Engine (sync) and Repayment Reconciliation → Paddle
- Java 21 virtual threads enabled (`spring.threads.virtual.enabled=true`) — free throughput on blocking I/O without a reactive rewrite
- Redis-backed `@Cacheable` on the score lookup

**Observability**
- Spring Boot Actuator health/readiness/liveness endpoints on every service, wired into k3s probes
- Structured (JSON) logs with a correlation/trace ID propagated from the gateway through every downstream call
- Full metrics dashboards (Prometheus/Grafana) or distributed tracing are a later bolt-on, not v1 — doesn't touch the data model or APIs, so nothing is lost by deferring it

**Testing**
- Testcontainers-backed integration tests per service (real Postgres/Kafka, not mocks)

## 10. Kafka Event Contracts
**Serialization**: JSON with a versioned envelope for v1, not Avro + Schema Registry — same "defer the heavier infra" call as Grafana/Prometheus. Every event shares:
```json
{
  "eventId": "uuid-v7",
  "eventType": "application.approved",
  "occurredAt": "ISO-8601",
  "aggregateId": "the entity this event is about",
  "schemaVersion": 1,
  "payload": { }
}
```

**Topics**
| Topic | Published by | Partition key | Consumed by | Payload highlights |
|---|---|---|---|---|
| `applications.approved` | Application Service | `applicationId` | Repayment Reconciliation, Notifications | applicantId, merchantId, amount, installmentCount/amount |
| `applications.manual-review` | Application Service | `applicationId` | Notifications | applicantId, riskScore |
| `applications.declined` | Application Service | `applicationId` | Notifications | applicantId, riskScore |
| `repayments.installment-paid` | Repayment Reconciliation | `repaymentPlanId` | Notifications | installmentId, sequenceNumber, amount |
| `repayments.installment-missed` | Repayment Reconciliation | `repaymentPlanId` | Notifications | installmentId, sequenceNumber, dueDate |
| `repayments.plan-completed` | Repayment Reconciliation | `repaymentPlanId` | Application Service, Notifications | applicationId |
| `repayments.plan-defaulted` | Repayment Reconciliation | `repaymentPlanId` | Application Service, Notifications | applicationId |

Events are keyed by the relevant aggregate ID so Kafka guarantees per-entity ordering within a partition.

**Reliable publishing — transactional outbox**: Application Service and Repayment Reconciliation both write to Postgres and emit an event as one logical operation. Publishing directly inside the request risks silently losing the event on a crash between the DB commit and the Kafka send. Each gets an `outbox_events` table in its own schema: the event is written in the same transaction as the business data, and a scheduled poller reads unpublished rows and sends them. (Debezium/CDC on the Postgres WAL is the larger-scale version of this pattern — a poller is lighter infrastructure here and demonstrates the same guarantee.)

**Consumer idempotency** — not every consumer needs a dedup table:
- Repayment Reconciliation consuming `applications.approved`: a unique constraint on `repayment_plans.application_id` already makes a redelivery a harmless failed insert
- Application Service consuming `plan-completed`/`plan-defaulted`: re-setting the same status is naturally a no-op
- Notifications Service needs the `notification_log` table (from the schema section) since "send an alert" isn't naturally idempotent

## 11. API Contracts
**Orchestration decision:** the Credit Risk Engine owns its own calls to the Mock Bureau and Repayment History internally, rather than Application Service pre-fetching both and passing everything in one payload. This keeps Application Service's job simple (submit → get a decision) and keeps the Credit Risk Engine cohesive — "assemble what's needed and score it" as one responsibility. Tradeoff: the Credit Risk Engine owns two circuit breakers instead of Application Service owning three, which is the right place for that complexity to live.

**Fail-safe rule:** if either internal call fails (circuit open), the Credit Risk Engine defaults to `MANUAL_REVIEW` — never auto-approve or hard-fail the checkout on incomplete data.

**Public endpoints** (through the gateway, `/api/v1/...`, JWT required):
| Endpoint | Role | Notes |
|---|---|---|
| `POST /api/v1/applicants` | shopper (signup) | identity + payment method only |
| `POST /api/v1/applications` | shopper | the critical checkout path — see below |
| `GET /api/v1/applications/{id}` | shopper/ops | status lookup |
| `GET /api/v1/applications?status=MANUAL_REVIEW` | ops | review queue |
| `POST /api/v1/applications/{id}/review-decision` | ops | manual approve/decline |
| `GET /api/v1/merchants/{id}/payouts` | merchant | scoped to own `merchantId` via JWT claim |

**Checkout call** — `POST /api/v1/applications`
```
Headers: Authorization: Bearer <JWT>, Idempotency-Key: <uuid>
Body: { "merchantId": "uuid", "amount": 199.99 }
```
`applicantId` comes from the JWT subject, never the request body. Response:
```json
{ "applicationId": "uuid", "status": "APPROVED", "installmentCount": 4, "installmentAmount": 50.00, "decisionAt": "..." }
```

**Internal-only endpoints** — never routed through the gateway; unreachable from outside the cluster at the network level (plain ClusterIP, no Ingress), not just auth-gated:
- `POST /internal/score` (Credit Risk Engine) — `{ applicantId, amount, merchantCategory, requestedAt }` → `{ riskScore, decision, scoreFactors[] }`
- `GET /internal/bureau-profile/{applicantId}` (Mock Credit Bureau)
- `GET /internal/repayment-history/{applicantId}` (Repayment Reconciliation) — `{ completedPlans, defaultedPlans, latePaymentCount, onTimeRate }`

**DRY note:** the ops `review-decision` endpoint and the automated engine both terminate in the same place — publishing `applications.approved`/`applications.declined` and updating status. That's one shared "finalize decision" method called from either path, not duplicated logic.

**Cross-cutting:** `/api/v1` prefix from day one; consistent error shape via `@ControllerAdvice` — `{ "error": "VALIDATION_ERROR", "message": "...", "traceId": "..." }` — so the correlation ID from the observability section shows up in error responses too.

## 12. ML Plan
**Algorithm: Logistic Regression, not gradient-boosted trees.** Chosen specifically because `scoreFactors` (the per-decision explainability breakdown already committed to for the ops dashboard) needs an exact, real-time-computable answer. SHAP — the usual way to explain tree ensembles — needs the original tree structure and doesn't reproduce cleanly inside a Java service after ONNX export. A logistic regression's per-feature contribution is just `coefficient × scaled feature value`: exact, not approximated, zero extra libraries. This mirrors why real credit-scoring systems (FICO-style scorecards) use linear models — regulators require an exact, auditable answer to "why was this denied," not a black box.

**Dataset & split** — Kaggle's "Give Me Some Credit" (~150k rows, ~6.7% positive/default rate):
- 70% training
- 15% held-out evaluation
- 15% set aside, untouched by training, for the Mock Credit Bureau's simulation pool (see Section 8/Mock Credit Bureau Mechanics) — the bureau never hands out rows the model was trained on

**Preprocessing**
- Median-impute missing `MonthlyIncome`; 0-impute missing `NumberOfDependents`
- Clip known sentinel/data-quality values in the past-due count columns (documented placeholder values like 96/98 that aren't real counts)
- `StandardScaler` + `LogisticRegression` bundled into one scikit-learn `Pipeline`, so a single ONNX export handles scaling and scoring in one graph — the Java service never reimplements scaling logic
- `class_weight='balanced'` given the imbalanced target

**Evaluation:** AUC-ROC as the headline metric (threshold-independent), plus precision/recall specifically at the 0.3/0.7 cutoffs, since those are the real approve/review/decline boundaries, not an abstract threshold.

**Export & serving:**
- `skl2onnx` exports the fitted pipeline to `.onnx` for the actual inference call
- Fitted coefficients + scaler params are exported separately to a small `coefficients.json`, loaded by the Credit Risk Engine at startup — used only to compute the per-feature `scoreFactors` breakdown as a plain dot product, not for the prediction itself
- Any unexpected exception from the ONNX inference call (malformed input, load failure) routes to `MANUAL_REVIEW`, same fail-safe principle as the internal-call failures in the API contracts section

**Deliberately out of scope for v1:** live retraining pipelines, a model registry, A/B testing between model versions. A versioned, manually-retrained `.onnx` file loaded at startup is enough — same "basic now, add later if it matters" pattern as Terraform and Grafana.

## 13. Kubernetes Layout
**Namespaces:** two — `riskgate` (business services) and `platform` (Postgres, Kafka, Redis, Keycloak). Enough separation for sane `kubectl` filtering and narrower RBAC scoping, without a namespace-per-bounded-context scheme that only pays off with a team and multiple environments.

**Storage:** k3s's built-in `local-path` provisioner, used as-is. Normally "local path" on cloud infra is risky because it often means ephemeral instance storage — but this instance is block-storage-only, so the local path is itself durable OCI block storage. No separate CSI driver needed at this scale.

**Deployment vs. StatefulSet:** Postgres, Kafka, Redis, and Keycloak are textbook StatefulSet candidates, but that pattern earns its complexity specifically at multiple replicas (stable identity, ordered scaling, per-replica volumes). At one replica each, a plain `Deployment` + a single PVC does the same job with less to configure — StatefulSet is "the right tool the day one of these is actually clustered," not before.

**Kafka runs in KRaft mode**, not with Zookeeper — Kafka has deprecated the Zookeeper dependency in recent versions, so this is one fewer stateful component, not a shortcut.

**Resource requests/limits** (starting budget, plenty of headroom against the 24GB dedicated to this project):

| Workload | Request | Limit |
|---|---|---|
| Postgres | 256Mi | 512Mi |
| Kafka (KRaft, single broker) | 512Mi | 1Gi |
| Redis | 64Mi | 128Mi |
| Keycloak | 384Mi | 512Mi |
| Each Spring Boot service (×7) | 256Mi | 384Mi |
| Angular apps (nginx, static) (×2) | 32Mi | 64Mi |

Roughly 5GB at limits total. JVM services use `-XX:MaxRAMPercentage=75.0` rather than a hardcoded `-Xmx` — JDK 17+ reads the container's cgroup limit automatically, so heap sizing tracks the pod's memory limit if it ever changes.

**Probes:** Spring Boot Actuator's liveness/readiness health groups wired into every Deployment, plus a `startupProbe` so a still-booting JVM isn't mistaken for a dead one.

**Networking:** k3s's bundled Traefik ingress controller (no separate nginx-ingress install). One Ingress, path-based routing under a single domain (`/api/**` → gateway, `/` → main Angular app, `/storefront` → demo storefront), reusing the existing cert-manager + Let's Encrypt setup from TruckNest — one DNS entry, one certificate.

**Secrets:** plain k3s Secret objects (DB passwords, Paddle keys, Keycloak admin credentials) — sufficient at this scale; Sealed Secrets/SOPS is the "real production" upgrade, deferred like Terraform and Grafana.

**Build detail easy to forget:** images must be built for `linux/arm64` (Ampere, not x86) — `docker buildx build --platform linux/arm64` via QEMU in CI, since GitHub Actions' default runners are x86_64.

**Deploy mechanism:** consistent with the "no Helm" decision — GitHub Actions builds and pushes images to GHCR, then SSHs into the box and runs `kubectl apply -f k8s/` using a kubeconfig stored as a repo secret.

## 14. Status
This is the original planning document. All seven backend services, both Angular apps, the Keycloak realm, the trained model and the CI pipeline are built; see the root `README.md` for current status. Kubernetes deployment (§13) is in progress.
