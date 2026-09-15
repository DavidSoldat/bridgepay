# BridgePay Progress

## Done
- [x] Full architecture / DB schema / Kafka contract / API contract / ML plan / K8s layout planning — `docs/bridgepay-platform-spec.md`
- [x] Applicant Service — signup + profile lookup. No Kafka, by design. Hit and fixed Boot 4.1.1 starter-rename compile errors locally.
- [x] Application Service — checkout flow, transactional outbox, `CreditRiskClient` port with a programmatic Resilience4j circuit breaker (fail-safe fallback to `MANUAL_REVIEW`), ops review flow sharing one "finalize decision" path with the automated engine. Not yet compiled/verified locally.

## Next, in order
- [ ] Verify Application Service builds: `cd services/application-service && mvn clean verify` — check its README's flagged risk areas first if anything fails
- [ ] Credit Risk Engine — standalone Spring service, loads the ONNX model + `coefficients.json`, exposes `POST /internal/score`, itself calls the Mock Credit Bureau and Repayment Reconciliation internally
- [ ] Mock Credit Bureau Service — deterministic hash-based lookup into a static pool derived from the Give Me Some Credit dataset
- [ ] Train the actual model — Python, scikit-learn `LogisticRegression` (chosen for exact per-feature explainability, not accuracy), `skl2onnx` export — spec § 12
- [ ] Notifications Service
- [ ] Repayment Reconciliation Service — Paddle integration for shopper installment collection
- [ ] Wire Kafka for real across services — only Application Service has the outbox table so far; no broker has actually been run yet
- [ ] Angular frontend (main app + storefront demo), Keycloak realm export for local dev
- [ ] k3s manifests, GitHub Actions CI/CD with ARM64 image builds

## Known open risks, unverified (compile-time)
- `application-service`'s `SecurityConfig`: `JwtGrantedAuthoritiesConverter` / `JwtAuthenticationToken` package paths were written from Spring Security 6-era memory — Spring Security 7 (paired with Boot 4) reportedly went through its own modularization pass
- Whether `spring-boot-starter-flyway` alone covers Postgres, or whether `flyway-database-postgresql` still needs adding alongside it
- Resilience4j: no confirmed Boot 4-compatible Spring integration module exists yet — currently using the framework-agnostic core library wired by hand instead of annotations. Worth revisiting if/when a Boot4-compatible integration ships.
