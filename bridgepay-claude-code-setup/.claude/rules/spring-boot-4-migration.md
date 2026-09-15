---
paths:
  - "services/**/pom.xml"
  - "services/**/*.java"
---

# Spring Boot 4.1.1 migration notes

Confirmed against the official Boot 4 migration guide and GitHub issues while building this project — not guessed:

- `spring-boot-starter-web` → `spring-boot-starter-webmvc`
- `spring-boot-starter-oauth2-resource-server` → `spring-boot-starter-security-oauth2-resource-server`
- `spring-security-test` (`org.springframework.security`) → `spring-boot-starter-security-test` (`org.springframework.boot`)
- `flyway-core` + `flyway-database-postgresql` → `spring-boot-starter-flyway` (`org.springframework.boot`) — **unconfirmed** whether the Postgres dialect artifact is still needed alongside it
- `spring-kafka` (`org.springframework.kafka`) → `spring-boot-starter-kafka` (`org.springframework.boot`)
- `@AutoConfigureMockMvc` moved from `org.springframework.boot.test.autoconfigure.web.servlet` to `org.springframework.boot.webmvc.test.autoconfigure`, and now needs its own dependency: `spring-boot-starter-webmvc-test`
- `resilience4j-spring-boot3`'s dependency chain was still pinned to Spring Framework 6 as of this project's build — no confirmed Boot 4 support. This codebase uses `resilience4j-circuitbreaker` (core, framework-agnostic) wired programmatically instead of the annotation-based integration. See `services/application-service/.../client/HttpCreditRiskClient.java` for the pattern — reuse it for the Credit Risk Engine rather than re-litigating this.
- Older starter names mostly still resolve (deprecated, not yet removed) — **except** where a class was outright relocated (e.g. `AutoConfigureMockMvc`). For those, the old artifact plus the old import path won't compile at all, no matter which starter name is used.

If a `cannot find symbol` shows up on a Spring class that used to exist: search for "Spring Boot 4 migration" plus the class name before guessing a fix. Don't assume Boot 3-era package layouts just because they're more familiar.
