# API Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `services/api-gateway`, the 7th backend service — the single `/api/v1/**` entry point in front of Applicant Service and Application Service, doing path-based routing, gateway-level JWT validation, a global rate limit, and correlation-ID generation/forwarding.

**Architecture:** Spring Cloud Gateway Server MVC (servlet-based, not the reactive/WebFlux flavor) on Spring Boot 4.1.1, so it stays on the same blocking + virtual-threads model as every other service. Two hand-wired servlet `Filter`s (correlation ID, then rate limiting) run ahead of Gateway's own YAML-configured routing. A standard `SecurityConfig`/`LocalDevSecurityConfig` pair (copied from Application Service's reference JWT converter) rejects bad tokens before anything is proxied.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Cloud Gateway Server MVC (`spring-cloud-starter-gateway-server-webmvc`, via `spring-cloud-dependencies:2025.1.3` — confirmed against Spring's own release-train compatibility table as the first Spring Cloud train supporting Spring Boot 4.1.x), Resilience4j core `resilience4j-ratelimiter` (programmatic, no Spring integration module — same reason as every other Resilience4j use in this project), WireMock (`wiremock-standalone`, already used by Repayment Reconciliation Service) for routing tests.

**Spec:** `docs/superpowers/specs/2026-09-19-api-gateway-design.md`

## Global Constraints

- Java 21, Spring Boot `4.1.1` parent, Maven, package root `com.bridgepay.gateway`.
- `spring.threads.virtual.enabled=true`.
- UUIDv7 (`UuidCreator.getTimeOrderedEpoch()`) is NOT used anywhere in this service — there are no persisted entities, and the one generated ID (the correlation ID) is a log-correlation token, not a primary key, so a plain `UUID.randomUUID()` is correct here per CLAUDE.md's actual rule ("UUIDv7 for all primary keys").
- Every inbound DTO would need `@Valid` if this service had any — it doesn't; it has no request bodies of its own, only proxied ones.
- Global error shape via `@RestControllerAdvice`: `{error, message, traceId, timestamp}` — reuse the exact `ApiError`/`GlobalExceptionHandler` pattern already in all 6 other services (`traceId` from `MDC.get("traceId")`, falling back to a fresh random UUID).
- Two security configs: `SecurityConfig` (`@Profile("!local")`, real JWT resource server, `realm_access.roles`-aware `JwtAuthenticationConverter` copied verbatim from Application Service's `SecurityConfig`) and `LocalDevSecurityConfig` (`@Profile("local")`, permitAll).
- Docker images: `docker buildx build --platform linux/arm64` (not asserted in this plan's steps individually, but the Dockerfile task must produce an image buildable that way).
- No cross-schema FK / no Kafka in this service — moot, it has no datastore and no events.
- Never guess a Spring Boot 4/Spring Cloud package or artifact name — every dependency coordinate in this plan was confirmed by a live web search against Spring's own docs/release notes/Maven Central during brainstorming, not recalled from training data. If `mvn` reports a coordinate doesn't exist, stop and search again rather than guessing a substitute.

---

## File Structure

```
services/api-gateway/
  pom.xml
  mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties   (copied verbatim from another service)
  .gitignore, .gitattributes, .dockerignore                (copied verbatim from another service)
  Dockerfile
  docker-compose.yml
  README.md
  src/main/java/com/bridgepay/gateway/
    ApiGatewayApplication.java
    config/SecurityConfig.java
    config/LocalDevSecurityConfig.java
    filter/CorrelationIdFilter.java
    filter/RateLimitFilter.java
    web/ApiError.java
    web/GlobalExceptionHandler.java
  src/main/resources/application.yaml
  src/test/java/com/bridgepay/gateway/
    ApiGatewayApplicationTests.java
    RoutingIntegrationTest.java
    filter/CorrelationIdFilterTest.java
    filter/RateLimitFilterTest.java
    SecurityIntegrationTest.java
```

- `filter/` — the two hand-wired cross-cutting `Filter`s. Each does one thing (correlation ID vs. rate limiting), each independently testable via a fake `FilterChain`.
- `config/` — the two security configs, same split as every other service.
- `web/` — the shared error-response contract, same split as every other service.
- Routing itself has no Java class — it's declared entirely in `application.yaml` (`spring.cloud.gateway.server.webmvc.routes`), which is the normal way to configure Gateway Server MVC and keeps route changes a config diff, not a code diff.

---

### Task 1: Scaffold the service and prove the dependency set boots

This is the highest-risk task: Spring Cloud Gateway Server MVC has never been used in this project, and its compatibility with Spring Boot 4.1.1 specifically (not just 4.0.x) is asserted by Spring's compatibility table but not yet proven against this project's exact dependency set. Get a minimal service to boot before writing any real logic.

**Files:**
- Create: `services/api-gateway/pom.xml`
- Create: `services/api-gateway/mvnw`, `services/api-gateway/mvnw.cmd`, `services/api-gateway/.mvn/wrapper/maven-wrapper.properties`
- Create: `services/api-gateway/.gitignore`, `services/api-gateway/.gitattributes`, `services/api-gateway/.dockerignore`
- Create: `services/api-gateway/src/main/java/com/bridgepay/gateway/ApiGatewayApplication.java`
- Create: `services/api-gateway/src/main/java/com/bridgepay/gateway/config/LocalDevSecurityConfig.java`
- Create: `services/api-gateway/src/main/resources/application.yaml`
- Test: `services/api-gateway/src/test/java/com/bridgepay/gateway/ApiGatewayApplicationTests.java`

**Interfaces:**
- Produces: `LocalDevSecurityConfig` (package `com.bridgepay.gateway.config`) — permitAll filter chain, `@Profile("local")`, reused as-is by every later task's tests via `@ActiveProfiles("local")`.

- [ ] **Step 1: Copy the generic project files from an existing service**

```bash
cp services/mock-credit-bureau/mvnw services/api-gateway/mvnw
cp services/mock-credit-bureau/mvnw.cmd services/api-gateway/mvnw.cmd
mkdir -p services/api-gateway/.mvn/wrapper
cp services/mock-credit-bureau/.mvn/wrapper/maven-wrapper.properties services/api-gateway/.mvn/wrapper/maven-wrapper.properties
cp services/mock-credit-bureau/.gitignore services/api-gateway/.gitignore
cp services/mock-credit-bureau/.gitattributes services/api-gateway/.gitattributes
chmod +x services/api-gateway/mvnw
```

- [ ] **Step 2: Write `.dockerignore`**

```
target/
.git/
.idea/
*.iml
```

- [ ] **Step 3: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>
	<parent>
		<groupId>org.springframework.boot</groupId>
		<artifactId>spring-boot-starter-parent</artifactId>
		<version>4.1.1</version>
		<relativePath/>
	</parent>
	<groupId>com.bridgepay</groupId>
	<artifactId>api-gateway</artifactId>
	<version>0.1.0-SNAPSHOT</version>
	<name>api-gateway</name>
	<description>BridgePay API Gateway - routing, rate limiting, gateway-level JWT validation for /api/v1/**</description>
	<url/>
	<licenses>
		<license/>
	</licenses>
	<developers>
		<developer/>
	</developers>
	<scm>
		<connection/>
		<developerConnection/>
		<tag/>
		<url/>
	</scm>
	<properties>
		<java.version>21</java.version>
		<spring-cloud.version>2025.1.3</spring-cloud.version>
		<resilience4j.version>2.4.0</resilience4j.version>
		<wiremock.version>3.13.1</wiremock.version>
	</properties>
	<dependencies>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-actuator</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.cloud</groupId>
			<artifactId>spring-cloud-starter-gateway-server-webmvc</artifactId>
		</dependency>
		<dependency>
			<groupId>io.github.resilience4j</groupId>
			<artifactId>resilience4j-ratelimiter</artifactId>
			<version>${resilience4j.version}</version>
		</dependency>

		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-actuator-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<!-- Paddle-can't-run-in-a-container reasoning doesn't apply here, but
			     the two downstream services this gateway proxies to can't easily
			     run inside this service's own test JVM either - wiremock-standalone
			     stubs their HTTP shape, same as HttpPaddleClientWireMockTest. -->
			<groupId>org.wiremock</groupId>
			<artifactId>wiremock-standalone</artifactId>
			<version>${wiremock.version}</version>
			<scope>test</scope>
		</dependency>
	</dependencies>

	<dependencyManagement>
		<dependencies>
			<dependency>
				<groupId>org.springframework.cloud</groupId>
				<artifactId>spring-cloud-dependencies</artifactId>
				<version>${spring-cloud.version}</version>
				<type>pom</type>
				<scope>import</scope>
			</dependency>
		</dependencies>
	</dependencyManagement>

	<build>
		<plugins>
			<plugin>
				<groupId>org.springframework.boot</groupId>
				<artifactId>spring-boot-maven-plugin</artifactId>
			</plugin>
		</plugins>
	</build>

</project>
```

- [ ] **Step 4: Write the main application class**

```java
package com.bridgepay.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```

- [ ] **Step 5: Write `LocalDevSecurityConfig`**

```java
package com.bridgepay.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Convenience-only security config for local docker-compose use without a
 * running Keycloak. NEVER active in tests or any deployed environment.
 */
@Configuration
@EnableMethodSecurity
@Profile("local")
public class LocalDevSecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
```

- [ ] **Step 6: Write `application.yaml`**

```yaml
spring:
  application:
    name: api-gateway
  threads:
    virtual:
      enabled: true
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/bridgepay}

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true

server:
  port: 8086

---
spring:
  config:
    activate:
      on-profile: local
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration
```

- [ ] **Step 7: Write the context-loads test**

```java
package com.bridgepay.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class ApiGatewayApplicationTests {

    @Test
    void contextLoads() {
        // Proves the whole dependency set (Spring Cloud Gateway Server MVC +
        // Spring Security's OAuth2 resource server autoconfig, excluded here
        // via the local profile + our own JWT stack) resolves and boots
        // together on Spring Boot 4.1.1 - unverified before this task.
    }
}
```

- [ ] **Step 8: Run the build**

Run: `cd services/api-gateway && mvn clean verify`
Expected: `BUILD SUCCESS`, 1 test run, 0 failures. If a dependency coordinate 404s or the context fails to start, search for the current Spring Cloud/Boot 4.1.1 compatible name/version before guessing a fix — do not fall back to an older Spring Cloud train or the reactive Gateway without stopping to confirm with the user first, since that would contradict this plan's Global Constraints.

- [ ] **Step 9: Commit**

```bash
git add services/api-gateway
git commit -m "Scaffold api-gateway service, verify Spring Cloud Gateway Server MVC boots on Boot 4.1.1"
```

---

### Task 2: Path-based routing + 404 error shape

**Files:**
- Modify: `services/api-gateway/src/main/resources/application.yaml`
- Create: `services/api-gateway/src/main/java/com/bridgepay/gateway/web/ApiError.java`
- Create: `services/api-gateway/src/main/java/com/bridgepay/gateway/web/GlobalExceptionHandler.java`
- Test: `services/api-gateway/src/test/java/com/bridgepay/gateway/RoutingIntegrationTest.java`

**Interfaces:**
- Consumes: nothing from Task 1 beyond the booting service itself.
- Produces: `ApiError(String error, String message, String traceId, Instant timestamp)` and `GlobalExceptionHandler`, reused unchanged by Task 4 (rate limit filter writes the same JSON shape by hand) and Task 5 (JWT tests assert against real routing).

- [ ] **Step 1: Write the failing routing test**

```java
package com.bridgepay.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class RoutingIntegrationTest {

    static WireMockServer wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @BeforeAll
    static void startWireMock() {
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("bridgepay.applicant-service.base-url", wireMock::baseUrl);
        registry.add("bridgepay.application-service.base-url", wireMock::baseUrl);
    }

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @Test
    void routesApplicantPaths_toApplicantService() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/api/v1/applicants"))
                .willReturn(okJson("{\"id\":\"abc\"}")));

        mockMvc.perform(post("/api/v1/applicants")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"id\":\"abc\"}"));
    }

    @Test
    void routesApplicationPaths_toApplicationService_preservingQueryParams() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/api/v1/applications?status=MANUAL_REVIEW"))
                .willReturn(okJson("[]")));

        mockMvc.perform(get("/api/v1/applications").queryParam("status", "MANUAL_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void routesMerchantPaths_toApplicationService() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/merchants/m-1/payouts"))
                .willReturn(okJson("[]")));

        mockMvc.perform(get("/api/v1/merchants/m-1/payouts"))
                .andExpect(status().isOk());
    }

    @Test
    void unmatchedPath_returnsApiErrorShaped404() throws Exception {
        mockMvc.perform(get("/api/v1/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").exists());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd services/api-gateway && mvn test -Dtest=RoutingIntegrationTest`
Expected: FAIL — no routes configured yet, everything 404s including the paths that should succeed; the "unmatched path" test also fails because there's no `ApiError`/`GlobalExceptionHandler` yet to shape the 404 body.

- [ ] **Step 3: Write `ApiError`**

```java
package com.bridgepay.gateway.web;

import java.time.Instant;

public record ApiError(String error, String message, String traceId, Instant timestamp) {
}
```

- [ ] **Step 4: Write `GlobalExceptionHandler`**

```java
package com.bridgepay.gateway.web;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoHandlerFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error("NOT_FOUND", "No route for " + ex.getHttpMethod() + " " + ex.getRequestURL()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleDownstreamFailure(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(error("BAD_GATEWAY", "Downstream service unavailable"));
    }

    private ApiError error(String code, String message) {
        String traceId = MDC.get("traceId");
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        return new ApiError(code, message, traceId, Instant.now());
    }
}
```

- [ ] **Step 5: Add routing config and the two required MVC properties to `application.yaml`**

Add under the top-level `spring:` key (same document, before the `---` profile separator):

```yaml
  mvc:
    throw-exception-if-no-handler-found: true
  web:
    resources:
      add-mappings: false
  cloud:
    gateway:
      server:
        webmvc:
          routes:
            - id: applicants
              uri: ${bridgepay.applicant-service.base-url}
              predicates:
                - Path=/api/v1/applicants/**
            - id: applications
              uri: ${bridgepay.application-service.base-url}
              predicates:
                - Path=/api/v1/applications/**
            - id: merchants
              uri: ${bridgepay.application-service.base-url}
              predicates:
                - Path=/api/v1/merchants/**

bridgepay:
  applicant-service:
    base-url: ${APPLICANT_SERVICE_URL:http://localhost:8080}
  application-service:
    base-url: ${APPLICATION_SERVICE_URL:http://localhost:8081}
```

`spring.mvc.throw-exception-if-no-handler-found` + `spring.web.resources.add-mappings: false` is the standard pair needed so an unmatched request throws `NoHandlerFoundException` (caught above) instead of silently falling through to Spring Boot's default static-resource 404 handler.

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd services/api-gateway && mvn test -Dtest=RoutingIntegrationTest`
Expected: PASS, all 4 tests green. If `NoHandlerFoundException` still isn't thrown, check that both `spring.mvc.throw-exception-if-no-handler-found` and `spring.web.resources.add-mappings` property names still exist under those keys in Boot 4.1.1 (search before assuming a rename — see Global Constraints).

- [ ] **Step 7: Run the full suite**

Run: `cd services/api-gateway && mvn clean verify`
Expected: `BUILD SUCCESS`, all tests green.

- [ ] **Step 8: Commit**

```bash
git add services/api-gateway
git commit -m "Add path-based routing to Applicant/Application Service and 404 error shape"
```

---

### Task 3: Correlation ID filter

**Files:**
- Create: `services/api-gateway/src/main/java/com/bridgepay/gateway/filter/CorrelationIdFilter.java`
- Test: `services/api-gateway/src/test/java/com/bridgepay/gateway/filter/CorrelationIdFilterTest.java`
- Modify: `services/api-gateway/src/test/java/com/bridgepay/gateway/RoutingIntegrationTest.java` (add one end-to-end forwarding assertion)

**Interfaces:**
- Produces: `CorrelationIdFilter.HEADER` constant (`"X-Correlation-Id"`, package-visible `static final String`), reused by `RateLimitFilter`'s test in Task 4 and by the end-to-end assertion added here.

- [ ] **Step 1: Write the failing filter unit test**

```java
package com.bridgepay.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesACorrelationId_whenNoneIsSent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/applicants");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<HttpServletRequest> forwarded = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain).doFilter(forwarded.capture(), any());
        String generated = forwarded.getValue().getHeader(CorrelationIdFilter.HEADER);
        assertThat(generated).isNotBlank();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(generated);
    }

    @Test
    void keepsTheClientsCorrelationId_whenOneIsSent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/applicants");
        request.addHeader(CorrelationIdFilter.HEADER, "client-supplied-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("client-supplied-id");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd services/api-gateway && mvn test -Dtest=CorrelationIdFilterTest`
Expected: FAIL with "cannot find symbol: class CorrelationIdFilter".

- [ ] **Step 3: Write `CorrelationIdFilter`**

```java
package com.bridgepay.gateway.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

/**
 * Reads X-Correlation-Id if the client sent one, otherwise generates one -
 * this is a log-correlation token, not a database primary key, so plain
 * UUID.randomUUID() is correct here (UUIDv7 is for primary keys only, per
 * CLAUDE.md). Runs before RateLimitFilter so rate-limited responses still
 * carry a trace ID.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements Filter {

    public static final String HEADER = "X-Correlation-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String correlationId = httpRequest.getHeader(HEADER);
        boolean generated = correlationId == null || correlationId.isBlank();
        if (generated) {
            correlationId = UUID.randomUUID().toString();
        }
        httpResponse.setHeader(HEADER, correlationId);
        MDC.put("traceId", correlationId);

        try {
            ServletRequest toForward = generated
                    ? new CorrelationIdRequestWrapper(httpRequest, correlationId)
                    : request;
            chain.doFilter(toForward, response);
        } finally {
            MDC.remove("traceId");
        }
    }

    private static final class CorrelationIdRequestWrapper extends HttpServletRequestWrapper {
        private final String correlationId;

        CorrelationIdRequestWrapper(HttpServletRequest request, String correlationId) {
            super(request);
            this.correlationId = correlationId;
        }

        @Override
        public String getHeader(String name) {
            return HEADER.equalsIgnoreCase(name) ? correlationId : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (HEADER.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(correlationId));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            if (names.stream().noneMatch(HEADER::equalsIgnoreCase)) {
                names.add(HEADER);
            }
            return Collections.enumeration(names);
        }
    }
}
```

- [ ] **Step 4: Run the unit test to verify it passes**

Run: `cd services/api-gateway && mvn test -Dtest=CorrelationIdFilterTest`
Expected: PASS.

- [ ] **Step 5: Add the end-to-end forwarding assertion to `RoutingIntegrationTest`**

Add this test method to the existing class from Task 2:

```java
    @Test
    void generatesAndForwardsACorrelationId_whenClientSendsNone() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/api/v1/applicants")).willReturn(okJson("{}")));

        String responseCorrelationId = mockMvc.perform(post("/api/v1/applicants")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("X-Correlation-Id");

        assertThat(responseCorrelationId).isNotBlank();
        wireMock.verify(postRequestedFor(urlEqualTo("/api/v1/applicants"))
                .withHeader("X-Correlation-Id", equalTo(responseCorrelationId)));
    }
```

Add the two missing static imports at the top of the file: `import static org.assertj.core.api.Assertions.assertThat;` and extend the existing `com.github.tomakehurst.wiremock.client.WireMock.*` static import (already a wildcard, so `postRequestedFor`/`equalTo` are already covered).

- [ ] **Step 6: Run the full suite**

Run: `cd services/api-gateway && mvn clean verify`
Expected: `BUILD SUCCESS`, all tests green.

- [ ] **Step 7: Commit**

```bash
git add services/api-gateway
git commit -m "Generate and forward a correlation ID on every gateway request"
```

---

### Task 4: Rate limiting filter

**Files:**
- Create: `services/api-gateway/src/main/java/com/bridgepay/gateway/filter/RateLimitFilter.java`
- Test: `services/api-gateway/src/test/java/com/bridgepay/gateway/filter/RateLimitFilterTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks (writes its own `ApiError`-shaped JSON body by hand — a `Filter` runs ahead of `DispatcherServlet`, so `GlobalExceptionHandler` from Task 2 cannot catch anything thrown here).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing filter unit test**

```java
package com.bridgepay.gateway.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    @Test
    void allowsRequestsUpToTheLimit_thenReturns429() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(2);
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(200); // untouched by the filter on the allowed path
        }
        verify(chain, times(2)).doFilter(any(), any());

        MockHttpServletRequest thirdRequest = new MockHttpServletRequest();
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(thirdRequest, thirdResponse, chain);

        assertThat(thirdResponse.getStatus()).isEqualTo(429);
        assertThat(thirdResponse.getContentAsString()).contains("RATE_LIMITED");
        verify(chain, times(2)).doFilter(any(), any()); // still 2 - the 3rd never reached the chain
    }
}
```

Add the missing static import: `import static org.mockito.ArgumentMatchers.any;`

- [ ] **Step 2: Run it to verify it fails**

Run: `cd services/api-gateway && mvn test -Dtest=RateLimitFilterTest`
Expected: FAIL with "cannot find symbol: class RateLimitFilter".

- [ ] **Step 3: Write `RateLimitFilter`**

```java
package com.bridgepay.gateway.filter;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One global Resilience4j core RateLimiter (no Spring integration module -
 * same reason as every other Resilience4j use in this project: no confirmed
 * Boot 4-compatible annotation-based module exists). Global rather than
 * per-client-IP: a single gateway replica per spec section 13 doesn't
 * justify a keyed-limiter registry.
 * ponytail: global rate limit, switch to a per-client-IP keyed
 * RateLimiterRegistry if abuse/noisy-neighbor traffic ever shows up.
 *
 * Runs after CorrelationIdFilter (higher Order value = lower precedence), so
 * a 429 response still carries a real trace ID in its hand-written body -
 * this filter runs before DispatcherServlet, so GlobalExceptionHandler
 * cannot shape this response; the JSON is written directly instead.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter implements Filter {

    private final RateLimiter rateLimiter;

    public RateLimitFilter(@Value("${gateway.rate-limit.requests-per-second:50}") int requestsPerSecond) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(requestsPerSecond)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build();
        this.rateLimiter = RateLimiterRegistry.of(config).rateLimiter("api-gateway");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (rateLimiter.acquirePermission()) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setStatus(429);
        httpResponse.setContentType("application/json");
        String traceId = MDC.get("traceId");
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        httpResponse.getWriter().write("""
                {"error":"RATE_LIMITED","message":"Too many requests","traceId":"%s","timestamp":"%s"}"""
                .formatted(traceId, Instant.now()));
    }
}
```

- [ ] **Step 4: Run the unit test to verify it passes**

Run: `cd services/api-gateway && mvn test -Dtest=RateLimitFilterTest`
Expected: PASS.

- [ ] **Step 5: Run the full suite**

Run: `cd services/api-gateway && mvn clean verify`
Expected: `BUILD SUCCESS`, all tests green (the default `50`/second limit from `application.yaml` won't trip during `RoutingIntegrationTest`'s handful of requests).

- [ ] **Step 6: Commit**

```bash
git add services/api-gateway
git commit -m "Add a global Resilience4j rate limiter ahead of routing"
```

---

### Task 5: Real gateway-level JWT validation

**Files:**
- Create: `services/api-gateway/src/main/java/com/bridgepay/gateway/config/SecurityConfig.java`
- Test: `services/api-gateway/src/test/java/com/bridgepay/gateway/SecurityIntegrationTest.java`

**Interfaces:**
- Consumes: the routes from Task 2 (a real route must exist to prove a `401` happens before proxying, not because of a 404).

- [ ] **Step 1: Write the failing security test**

```java
package com.bridgepay.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    static WireMockServer wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @BeforeAll
    static void startWireMock() {
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("bridgepay.applicant-service.base-url", wireMock::baseUrl);
        registry.add("bridgepay.application-service.base-url", wireMock::baseUrl);
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @TestConfiguration
    static class TestOverrides {
        // No real Keycloak in tests - same stub-JwtDecoder pattern already
        // used by Application Service's own integration tests.
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(60))
                    .build();
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void rejectsARequestWithNoToken() throws Exception {
        mockMvc.perform(post("/api/v1/applicants").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsARequestWithAValidToken_andRoutesIt() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/api/v1/applicants")).willReturn(okJson("{}")));

        mockMvc.perform(post("/api/v1/applicants")
                        .with(jwt())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd services/api-gateway && mvn test -Dtest=SecurityIntegrationTest`
Expected: FAIL — with no `SecurityConfig` (`@Profile("!local")`) yet, the default Spring Security autoconfiguration (or none at all, depending on what's on the classpath) doesn't match this test's expectations; specifically `rejectsARequestWithNoToken` fails because nothing is enforcing authentication on `!local`.

- [ ] **Step 3: Write `SecurityConfig`**

```java
package com.bridgepay.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Gateway-level JWT validation (spec section 4/9): rejects a missing/
 * expired/malformed token with 401 before anything is proxied downstream.
 * Deliberately does NOT do role checks - this service has no business
 * logic, so @PreAuthorize role enforcement stays exactly where it already
 * is, on the 6 downstream services (defense in depth: they keep validating
 * the same token again). Active everywhere except the "local" profile - see
 * LocalDevSecurityConfig. Copied from Application Service's SecurityConfig,
 * the reference implementation named in CLAUDE.md.
 */
@Configuration
@EnableMethodSecurity
@Profile("!local")
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthConverter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter)));
        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthConverter() {
        JwtGrantedAuthoritiesConverter defaultConverter = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(defaultConverter.convert(jwt));
            authorities.addAll(extractRealmRoles(jwt));
            return authorities;
        });
        return converter;
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> extractRealmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof List<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.toString().toUpperCase()))
                .collect(Collectors.toList());
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd services/api-gateway && mvn test -Dtest=SecurityIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Run the full suite**

Run: `cd services/api-gateway && mvn clean verify`
Expected: `BUILD SUCCESS`, all tests green across all 5 test classes.

- [ ] **Step 6: Commit**

```bash
git add services/api-gateway
git commit -m "Add gateway-level JWT validation (401 before proxying)"
```

---

### Task 6: Dockerfile, docker-compose, README

**Files:**
- Create: `services/api-gateway/Dockerfile`
- Create: `services/api-gateway/docker-compose.yml`
- Create: `services/api-gateway/README.md`
- Modify: `docker-compose.yml` (repo root)

**Interfaces:** none — packaging only, no code.

- [ ] **Step 1: Write the Dockerfile**

```dockerfile
# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# Run stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/api-gateway-*.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

- [ ] **Step 2: Write the service's own `docker-compose.yml`**

```yaml
services:
  api-gateway:
    build: .
    environment:
      SPRING_PROFILES_ACTIVE: local
      APPLICANT_SERVICE_URL: http://localhost:8080
      APPLICATION_SERVICE_URL: http://localhost:8081
    ports:
      - "8086:8086"
```

- [ ] **Step 3: Add the `api-gateway` service to the root `docker-compose.yml`**

Add this block after the `notifications-service` entry (matching the existing services' `SPRING_PROFILES_ACTIVE: local` convention — none of the other 6 override `KEYCLOAK_ISSUER_URI` there either, since the `local` profile excludes the resource-server autoconfiguration entirely):

```yaml
  api-gateway:
    build: ./services/api-gateway
    depends_on:
      - applicant-service
      - application-service
    environment:
      SPRING_PROFILES_ACTIVE: local
      APPLICANT_SERVICE_URL: http://applicant-service:8080
      APPLICATION_SERVICE_URL: http://application-service:8081
    ports:
      - "8086:8086"
```

- [ ] **Step 4: Write the README**

```markdown
# BridgePay — API Gateway

The single `/api/v1/**` entry point in front of Applicant Service and
Application Service (spec section 4/7/13): path-based routing, a global
rate limit, and gateway-level JWT validation. Internal-only endpoints
(`/internal/**` on any service) are never routed here at all - they're
unreachable from outside the cluster at the network level, not just
auth-gated (spec section 11).

## Routes

| Path | Target |
|---|---|
| `/api/v1/applicants/**` | Applicant Service |
| `/api/v1/applications/**` | Application Service |
| `/api/v1/merchants/**` | Application Service |

## Run locally

```bash
docker-compose up --build
```

Uses the `local` Spring profile (JWT auth disabled) and defaults to
`localhost:8080`/`localhost:8081` for the two downstream services - run
their own `docker-compose.yml` files alongside this one, or run the whole
stack from the repo root instead.

## Run tests

```bash
mvn clean verify
```

`RoutingIntegrationTest` and `SecurityIntegrationTest` stub the two
downstream services with WireMock rather than requiring them to actually
run. `CorrelationIdFilterTest`/`RateLimitFilterTest` are plain unit tests
against a fake `FilterChain`.

## Known gap

No route for Repayment Reconciliation Service's public `/webhooks/paddle`
endpoint (a non-`/api/v1` path) - it's reached directly for now. Revisit
when the k3s Ingress rules are written.
```

- [ ] **Step 5: Verify the image builds for the target architecture**

Run: `cd services/api-gateway && docker buildx build --platform linux/arm64 -t api-gateway:local .`
Expected: image builds successfully. (CLAUDE.md requires `linux/arm64` specifically since the target host is an Ampere ARM64 VM — a plain `docker build` would produce an image that doesn't run there.)

- [ ] **Step 6: Commit**

```bash
git add services/api-gateway docker-compose.yml
git commit -m "Add api-gateway Dockerfile, docker-compose wiring, and README"
```

---

### Task 7: Manual end-to-end smoke test and PROGRESS.md update

This task has no code changes — it proves the previous 6 tasks actually work together against the real shared stack, and records what was verified.

- [ ] **Step 1: Run the shared stack**

Run: `docker-compose up --build` from the repo root.
Expected: all containers start, including the new `api-gateway` on port 8086.

- [ ] **Step 2: Confirm actuator health**

Run: `curl -s http://localhost:8086/actuator/health`
Expected: `{"status":"UP"}` (or similar, matching every other service's actuator output).

- [ ] **Step 3: Confirm routing to Applicant Service through the gateway**

Run: `curl -s -X POST http://localhost:8086/api/v1/applicants -H "Content-Type: application/json" -d '{}'`
Expected: whatever response Applicant Service's real `POST /api/v1/applicants` gives directly on port 8080 for the same malformed/empty payload (likely a `400 VALIDATION_ERROR`) — the point is confirming the gateway actually reaches the container by its compose service name (`applicant-service:8080`), not a connection refused/502 from a misconfigured `APPLICANT_SERVICE_URL`.

- [ ] **Step 4: Confirm the correlation ID header round-trips**

Run: `curl -s -i http://localhost:8086/api/v1/applications -X GET | grep -i X-Correlation-Id`
Expected: an `X-Correlation-Id` header present on the response even though none was sent on the request.

- [ ] **Step 5: Confirm an unmatched path 404s with the shared `ApiError` shape**

Run: `curl -s http://localhost:8086/api/v1/does-not-exist`
Expected: `{"error":"NOT_FOUND", ...}`.

- [ ] **Step 6: Update `docs/PROGRESS.md`**

Move the "Spring Cloud Gateway" line from `## Next, in order` into `## Done`, following the exact style of every other entry in that file — cite the real `mvn clean verify` test count from this task's actual run, and describe what the manual smoke test in Steps 1-5 actually confirmed (real container-to-container routing over the compose network, not just MockMvc). Also update `## Next, in order` so "Main Angular app" is now first, since the gateway it depends on exists.

Do not write speculative claims (e.g. "confirmed working in k3s") — this service has only been run via `docker-compose`, and the k3s manifests task is still pending; say so, matching this file's existing habit of precisely scoping what was and wasn't verified (see the Repayment Reconciliation and Kafka-wiring entries for the style).

- [ ] **Step 7: Commit**

```bash
git add docs/PROGRESS.md
git commit -m "Mark API Gateway done in PROGRESS.md after end-to-end docker-compose smoke test"
```

---

## Self-Review Notes

- **Spec coverage:** routing table (Task 2), gateway-level JWT validation (Task 5), rate limiting (Task 4), correlation ID (Task 3), error shape (Tasks 2 & 4), docker-compose/Dockerfile (Task 6), testing depth comparable to Mock Credit Bureau (5 test classes across Tasks 1-5, all real HTTP behavior via WireMock/MockMvc, none mocked-away) — all covered. The spec's two explicit non-goals (MDC propagation into the other 6 services, `/webhooks/paddle` routing) are deliberately absent from every task and called out in the README (Task 6, Step 4) instead.
- **Placeholder scan:** none found — every step has runnable code or an exact shell command.
- **Type consistency:** `ApiError(error, message, traceId, timestamp)` from Task 2 is the one record used (by hand-written JSON matching its shape) in Task 4's `RateLimitFilter` — field names and order match. `CorrelationIdFilter.HEADER` (Task 3) is the constant referenced by name in Task 3's own added test; no other task references it by a different name.
