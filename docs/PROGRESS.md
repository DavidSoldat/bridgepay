# BridgePay Progress

## Done
- [x] Full architecture / DB schema / Kafka contract / API contract / ML plan / K8s layout planning — `docs/bridgepay-platform-spec.md`
- [x] Applicant Service — signup + profile lookup. No Kafka, by design. Hit and fixed Boot 4.1.1 starter-rename compile errors locally.
- [x] Application Service — checkout flow, transactional outbox, `CreditRiskClient` port with a programmatic Resilience4j circuit breaker (fail-safe fallback to `MANUAL_REVIEW`), ops review flow sharing one "finalize decision" path with the automated engine. Compiled and verified locally (`mvn clean verify`, 14/14 tests green) after fixing several Boot 4.1.1 issues — see `.claude/rules/spring-boot-4-migration.md`.

## Next, in order
- [ ] Credit Risk Engine — standalone Spring service, loads the ONNX model + `coefficients.json`, exposes `POST /internal/score`, itself calls the Mock Credit Bureau and Repayment Reconciliation internally
- [ ] Mock Credit Bureau Service — deterministic hash-based lookup into a static pool derived from the Give Me Some Credit dataset
- [ ] Train the actual model — Python, scikit-learn `LogisticRegression` (chosen for exact per-feature explainability, not accuracy), `skl2onnx` export — spec § 12
- [ ] Notifications Service
- [ ] Repayment Reconciliation Service — Paddle integration for shopper installment collection
- [ ] Wire Kafka for real across services — only Application Service has the outbox table so far; no broker has actually been run yet
- [ ] Angular frontend (main app + storefront demo), Keycloak realm export for local dev
- [ ] k3s manifests, GitHub Actions CI/CD with ARM64 image builds

## Known open risks, unverified (compile-time)
- Resilience4j: no confirmed Boot 4-compatible Spring integration module exists yet — currently using the framework-agnostic core library wired by hand instead of annotations. Worth revisiting if/when a Boot4-compatible integration ships.

Resolved during application-service's first `mvn clean verify` pass — see `.claude/rules/spring-boot-4-migration.md` for details: the `SecurityConfig` package paths, the `flyway-database-postgresql` question (yes, still needed alongside `spring-boot-starter-flyway`), Jackson 3 as Boot 4's default, and `RestClient.Builder` needing its own starter.
