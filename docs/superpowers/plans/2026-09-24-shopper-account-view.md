# Shopper Post-Purchase Account View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a logged-in shopper see their own past applications and, for approved ones, their installment payment schedule, in a new `/account` page in the storefront app.

**Architecture:** Two new read-only, subject-scoped GET endpoints (`application-service`'s `GET /api/v1/applications/me`, and repayment-reconciliation-service's first-ever shopper-facing endpoint, `GET /api/v1/repayment-plans/{applicationId}`), a new gateway route for the latter, and a new `/account` route in `frontend/storefront` (its first use of Angular Router) that lists applications and lazily loads each approved one's schedule on expand.

**Tech Stack:** Java 21 / Spring Boot 4.1.1 / Maven (backend), Angular 21 standalone components + signals / vitest (frontend). No new dependencies — `@angular/router` is already installed in storefront but unused until this plan.

**Spec:** `docs/superpowers/specs/2026-09-24-shopper-account-view-design.md`

## Global Constraints

- Backend: TDD with real Testcontainers (Postgres, and Kafka where the service already needs it for context startup) — never mock the database. Match each service's existing test conventions exactly (see each task).
- Backend: UUIDv7/`@Version`/JPA auditing are unaffected — this plan adds no new entities, only new read paths over existing ones.
- Backend: if a `cannot find symbol` shows up on a Spring class, check `.claude/rules/spring-boot-4-migration.md` before guessing — this plan doesn't expect to hit any new migration issue, but the file's precedents (e.g. `AutoConfigureMockMvc`'s package, Jackson 3 under `tools.jackson.databind`) apply to every new test file written here.
- Frontend: standalone components only, signals for local state, `toSignal`/`toObservable` for reactive service calls (matching `main-app`'s `ReviewQueue` pattern) — no `NgModule`, no `RxJS` subscriptions managed by hand.
- Frontend: every new/modified component gets a `.spec.ts` using the existing vitest + `TestBed` conventions already in this app (see `checkout/applications.spec.ts`, `main-app`'s `review-queue.spec.ts`).
- Commit after every task.

---

### Task 1: application-service — "list my applications" repository + service method

**Files:**
- Modify: `services/application-service/src/main/java/com/bridgepay/application/repository/CreditApplicationRepository.java`
- Modify: `services/application-service/src/main/java/com/bridgepay/application/service/CreditApplicationService.java`
- Test: `services/application-service/src/test/java/com/bridgepay/application/service/CreditApplicationServiceTest.java`

**Interfaces:**
- Produces: `CreditApplicationRepository.findByApplicantId(UUID applicantId, Pageable pageable): Page<CreditApplication>`
- Produces: `CreditApplicationService.listForApplicant(UUID applicantId, Pageable pageable): Page<ApplicationResponse>` (used by Task 2's controller)

- [ ] **Step 1: Write the failing test**

Add to `CreditApplicationServiceTest.java` (existing imports already cover everything used):

```java
    @Test
    void listForApplicant_returnsOnlyThatApplicantsApplications() {
        Pageable pageable = PageRequest.of(0, 20);
        CreditApplication mine = new CreditApplication(applicantId, merchant, new BigDecimal("100.00"));
        mine.applyDecision(ApplicationStatus.APPROVED, 0.1, "[]", 4, new BigDecimal("25.00"));
        when(applicationRepository.findByApplicantId(applicantId, pageable))
                .thenReturn(new PageImpl<>(List.of(mine)));

        Page<ApplicationResponse> result = service.listForApplicant(applicantId, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).applicantId()).isEqualTo(applicantId);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/application-service && mvn test -Dtest=CreditApplicationServiceTest`
Expected: FAIL with "cannot find symbol: method listForApplicant"

- [ ] **Step 3: Add the repository method**

In `CreditApplicationRepository.java`, add next to the existing `findByStatus`:

```java
    Page<CreditApplication> findByApplicantId(UUID applicantId, Pageable pageable);
```

- [ ] **Step 4: Add the service method**

In `CreditApplicationService.java`, add next to the existing `listApplications`:

```java
    @Transactional(readOnly = true)
    public Page<ApplicationResponse> listForApplicant(UUID applicantId, Pageable pageable) {
        return applicationRepository.findByApplicantId(applicantId, pageable).map(this::toResponse);
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd services/application-service && mvn test -Dtest=CreditApplicationServiceTest`
Expected: PASS (all tests in the file, not just the new one)

- [ ] **Step 6: Commit**

```bash
git add services/application-service/src/main/java/com/bridgepay/application/repository/CreditApplicationRepository.java services/application-service/src/main/java/com/bridgepay/application/service/CreditApplicationService.java services/application-service/src/test/java/com/bridgepay/application/service/CreditApplicationServiceTest.java
git commit -m "application-service: add listForApplicant for the shopper account view"
```

---

### Task 2: application-service — `GET /api/v1/applications/me` endpoint

**Files:**
- Modify: `services/application-service/src/main/java/com/bridgepay/application/web/CreditApplicationController.java`
- Test: `services/application-service/src/test/java/com/bridgepay/application/CreditApplicationControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `CreditApplicationService.listForApplicant(UUID, Pageable)` from Task 1
- Produces: `GET /api/v1/applications/me?page=&size=` → `200 Page<ApplicationResponse>`, filtered to the caller's own applications, for Task 8 (storefront `Applications.listMine`) to call through the gateway.

- [ ] **Step 1: Write the failing test**

Add to `CreditApplicationControllerIntegrationTest.java`:

```java
    @Test
    void listMine_returnsOnlyTheAuthenticatedShoppersOwnApplications() throws Exception {
        String subject = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/applications")
                        .with(jwt().jwt(j -> j.subject(subject)))
                        .header("Idempotency-Key", "idem-mine-1")
                        .contentType("application/json")
                        .content(checkoutPayload(merchantId.toString(), "60.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/applications")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))
                        .header("Idempotency-Key", "idem-mine-other")
                        .contentType("application/json")
                        .content(checkoutPayload(merchantId.toString(), "60.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/applications/me").with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].applicantId").value(subject));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/application-service && mvn test -Dtest=CreditApplicationControllerIntegrationTest`
Expected: FAIL with 404 (no handler for `GET /api/v1/applications/me`) — Spring resolves the literal `/me` mapping ahead of the `/{id}` pattern once it exists, so no route-ordering change is needed once Step 3 lands.

- [ ] **Step 3: Add the controller endpoint**

In `CreditApplicationController.java`, add next to the existing `get(...)`:

```java
    @GetMapping("/me")
    public ResponseEntity<Page<ApplicationResponse>> listMine(@AuthenticationPrincipal Jwt jwt, Pageable pageable) {
        return ResponseEntity.ok(applicationService.listForApplicant(UUID.fromString(jwt.getSubject()), pageable));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd services/application-service && mvn test -Dtest=CreditApplicationControllerIntegrationTest`
Expected: PASS

- [ ] **Step 5: Run the full service suite**

Run: `cd services/application-service && mvn clean verify`
Expected: PASS, all tests green (confirms no regression on the existing `/{id}` and OPS list endpoints)

- [ ] **Step 6: Commit**

```bash
git add services/application-service/src/main/java/com/bridgepay/application/web/CreditApplicationController.java services/application-service/src/test/java/com/bridgepay/application/CreditApplicationControllerIntegrationTest.java
git commit -m "application-service: add GET /api/v1/applications/me"
```

---

### Task 3: repayment-reconciliation-service — DTOs + `RepaymentPlanService.getForApplicant`

**Files:**
- Create: `services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/dto/RepaymentPlanResponse.java`
- Create: `services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/dto/InstallmentResponse.java`
- Modify: `services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/service/RepaymentPlanService.java`
- Test: `services/repayment-reconciliation-service/src/test/java/com/bridgepay/repayment/service/RepaymentPlanServiceTest.java`

**Interfaces:**
- Produces: `RepaymentPlanService.getForApplicant(UUID applicationId, UUID applicantId): RepaymentPlanResponse` — throws `java.util.NoSuchElementException` if no plan exists for that application, throws `org.springframework.security.access.AccessDeniedException` if the plan exists but belongs to a different applicant. Used by Task 4's controller.
- Produces: `record RepaymentPlanResponse(UUID planId, UUID applicationId, String status, BigDecimal totalAmount, int installmentCount, BigDecimal installmentAmount, List<InstallmentResponse> installments)`
- Produces: `record InstallmentResponse(int sequenceNumber, LocalDate dueDate, BigDecimal amount, String status, Instant paidAt)`

- [ ] **Step 1: Write the failing tests**

Add these imports to `RepaymentPlanServiceTest.java` alongside the existing ones:

```java
import com.bridgepay.repayment.domain.Installment;
import com.bridgepay.repayment.dto.RepaymentPlanResponse;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.NoSuchElementException;
```

Add these three tests to the class:

```java
    @Test
    void getForApplicant_returnsThePlanWithItsInstallmentsInOrder() {
        service = new RepaymentPlanService(repaymentPlanRepository, installmentRepository, applicantClient, paddleClient);
        UUID applicationId = UUID.randomUUID();
        UUID applicantId = UUID.randomUUID();
        RepaymentPlan plan = new RepaymentPlan(applicationId, applicantId, "ctm_1", "txn_1",
                new BigDecimal("200.00"), 4, new BigDecimal("50.00"));
        Installment first = new Installment(plan, 1, LocalDate.of(2026, 1, 1), new BigDecimal("50.00"));
        when(repaymentPlanRepository.findByApplicationId(applicationId)).thenReturn(Optional.of(plan));
        when(installmentRepository.findByRepaymentPlanOrderBySequenceNumberAsc(plan)).thenReturn(List.of(first));

        RepaymentPlanResponse response = service.getForApplicant(applicationId, applicantId);

        assertThat(response.applicationId()).isEqualTo(applicationId);
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.installments()).hasSize(1);
        assertThat(response.installments().get(0).sequenceNumber()).isEqualTo(1);
    }

    @Test
    void getForApplicant_throwsNoSuchElement_whenNoPlanExistsForThisApplication() {
        service = new RepaymentPlanService(repaymentPlanRepository, installmentRepository, applicantClient, paddleClient);
        UUID applicationId = UUID.randomUUID();
        when(repaymentPlanRepository.findByApplicationId(applicationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getForApplicant(applicationId, UUID.randomUUID()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getForApplicant_throwsAccessDenied_whenThePlanBelongsToAnotherApplicant() {
        service = new RepaymentPlanService(repaymentPlanRepository, installmentRepository, applicantClient, paddleClient);
        UUID applicationId = UUID.randomUUID();
        RepaymentPlan plan = new RepaymentPlan(applicationId, UUID.randomUUID(), "ctm_1", "txn_1",
                new BigDecimal("200.00"), 4, new BigDecimal("50.00"));
        when(repaymentPlanRepository.findByApplicationId(applicationId)).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> service.getForApplicant(applicationId, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd services/repayment-reconciliation-service && mvn test -Dtest=RepaymentPlanServiceTest`
Expected: FAIL with "cannot find symbol: method getForApplicant" / missing `RepaymentPlanResponse` class

- [ ] **Step 3: Create the DTOs**

`RepaymentPlanResponse.java`:

```java
package com.bridgepay.repayment.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record RepaymentPlanResponse(
        UUID planId,
        UUID applicationId,
        String status,
        BigDecimal totalAmount,
        int installmentCount,
        BigDecimal installmentAmount,
        List<InstallmentResponse> installments
) {
}
```

`InstallmentResponse.java`:

```java
package com.bridgepay.repayment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record InstallmentResponse(
        int sequenceNumber,
        LocalDate dueDate,
        BigDecimal amount,
        String status,
        Instant paidAt
) {
}
```

- [ ] **Step 4: Add the service method**

In `RepaymentPlanService.java`, add these imports:

```java
import com.bridgepay.repayment.dto.InstallmentResponse;
import com.bridgepay.repayment.dto.RepaymentPlanResponse;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.NoSuchElementException;
```

Add the method (and its private mapper) to the class:

```java
    @Transactional(readOnly = true)
    public RepaymentPlanResponse getForApplicant(UUID applicationId, UUID applicantId) {
        RepaymentPlan plan = repaymentPlanRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new NoSuchElementException("Repayment plan not found"));
        if (!plan.getApplicantId().equals(applicantId)) {
            throw new AccessDeniedException("Repayment plan does not belong to this applicant");
        }
        List<Installment> installments = installmentRepository.findByRepaymentPlanOrderBySequenceNumberAsc(plan);
        return new RepaymentPlanResponse(
                plan.getId(), plan.getApplicationId(), plan.getStatus().name(),
                plan.getTotalAmount(), plan.getInstallmentCount(), plan.getInstallmentAmount(),
                installments.stream().map(this::toInstallmentResponse).toList());
    }

    private InstallmentResponse toInstallmentResponse(Installment installment) {
        return new InstallmentResponse(installment.getSequenceNumber(), installment.getDueDate(),
                installment.getAmount(), installment.getStatus().name(), installment.getPaidAt());
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd services/repayment-reconciliation-service && mvn test -Dtest=RepaymentPlanServiceTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/dto/RepaymentPlanResponse.java services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/dto/InstallmentResponse.java services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/service/RepaymentPlanService.java services/repayment-reconciliation-service/src/test/java/com/bridgepay/repayment/service/RepaymentPlanServiceTest.java
git commit -m "repayment-reconciliation-service: add getForApplicant with DTOs"
```

---

### Task 4: repayment-reconciliation-service — `GET /api/v1/repayment-plans/{applicationId}` endpoint

**Files:**
- Create: `services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/web/RepaymentPlanController.java`
- Modify: `services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/web/GlobalExceptionHandler.java`
- Test: `services/repayment-reconciliation-service/src/test/java/com/bridgepay/repayment/web/RepaymentPlanControllerIntegrationTest.java` (new)

**Interfaces:**
- Consumes: `RepaymentPlanService.getForApplicant(UUID, UUID)` from Task 3
- Produces: `GET /api/v1/repayment-plans/{applicationId}` → `200 RepaymentPlanResponse` / `403` / `404`, for Task 6 (gateway route) and Task 8 (storefront `RepaymentPlans.getPlan`) to call through.

- [ ] **Step 1: Write the failing integration test**

Create `RepaymentPlanControllerIntegrationTest.java`:

```java
package com.bridgepay.repayment.web;

import com.bridgepay.repayment.domain.Installment;
import com.bridgepay.repayment.domain.RepaymentPlan;
import com.bridgepay.repayment.repository.InstallmentRepository;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Postgres + Kafka via Testcontainers, matching this service's other
 * @SpringBootTest classes (its Kafka consumer needs a broker to start).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RepaymentPlanControllerIntegrationTest.TestConfig.class)
class RepaymentPlanControllerIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bridgepay")
            .withUsername("test")
            .withPassword("test");

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:latest"));

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgresContainer() {
            return POSTGRES;
        }

        @Bean
        @ServiceConnection
        KafkaContainer kafkaContainer() {
            return KAFKA;
        }

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
    private MockMvc mockMvc;
    @Autowired
    private RepaymentPlanRepository repaymentPlanRepository;
    @Autowired
    private InstallmentRepository installmentRepository;

    @Test
    void get_returnsThePlanWithInstallments_whenItBelongsToTheAuthenticatedShopper() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        RepaymentPlan plan = repaymentPlanRepository.save(new RepaymentPlan(applicationId, UUID.fromString(subject),
                "ctm_1", "txn_1", new BigDecimal("200.00"), 4, new BigDecimal("50.00")));
        installmentRepository.save(new Installment(plan, 1, LocalDate.of(2026, 1, 1), new BigDecimal("50.00")));

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId)
                        .with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(applicationId.toString()))
                .andExpect(jsonPath("$.installments.length()").value(1))
                .andExpect(jsonPath("$.installments[0].sequenceNumber").value(1));
    }

    @Test
    void get_returnsForbidden_whenThePlanBelongsToSomeoneElse() throws Exception {
        UUID applicationId = UUID.randomUUID();
        repaymentPlanRepository.save(new RepaymentPlan(applicationId, UUID.randomUUID(),
                "ctm_2", "txn_2", new BigDecimal("100.00"), 4, new BigDecimal("25.00")));

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId)
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void get_returnsNotFound_whenNoPlanExistsForThisApplication() throws Exception {
        mockMvc.perform(get("/api/v1/repayment-plans/" + UUID.randomUUID())
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/repayment-reconciliation-service && mvn test -Dtest=RepaymentPlanControllerIntegrationTest`
Expected: FAIL — 404 with no handler (`RepaymentPlanController` doesn't exist yet)

- [ ] **Step 3: Create the controller**

```java
package com.bridgepay.repayment.web;

import com.bridgepay.repayment.dto.RepaymentPlanResponse;
import com.bridgepay.repayment.service.RepaymentPlanService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/repayment-plans")
public class RepaymentPlanController {

    private final RepaymentPlanService repaymentPlanService;

    public RepaymentPlanController(RepaymentPlanService repaymentPlanService) {
        this.repaymentPlanService = repaymentPlanService;
    }

    @GetMapping("/{applicationId}")
    public ResponseEntity<RepaymentPlanResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID applicationId) {
        RepaymentPlanResponse response =
                repaymentPlanService.getForApplicant(applicationId, UUID.fromString(jwt.getSubject()));
        return ResponseEntity.ok(response);
    }
}
```

- [ ] **Step 4: Map `AccessDeniedException` to 403 in `GlobalExceptionHandler`**

Add this import:

```java
import org.springframework.security.access.AccessDeniedException;
```

Add this handler (next to the existing `handleNotFound`):

```java
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleForbidden(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("FORBIDDEN", ex.getMessage()));
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd services/repayment-reconciliation-service && mvn test -Dtest=RepaymentPlanControllerIntegrationTest`
Expected: PASS

- [ ] **Step 6: Run the full service suite**

Run: `cd services/repayment-reconciliation-service && mvn clean verify`
Expected: PASS, all tests green

- [ ] **Step 7: Commit**

```bash
git add services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/web/RepaymentPlanController.java services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/web/GlobalExceptionHandler.java services/repayment-reconciliation-service/src/test/java/com/bridgepay/repayment/web/RepaymentPlanControllerIntegrationTest.java
git commit -m "repayment-reconciliation-service: add GET /api/v1/repayment-plans/{applicationId}"
```

---

### Task 5: repayment-reconciliation-service — local-profile JWT stub for the new endpoint

**Files:**
- Modify: `services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/config/LocalDevSecurityConfig.java`
- Test: `services/repayment-reconciliation-service/src/test/java/com/bridgepay/repayment/LocalProfileSecurityIntegrationTest.java` (new)

**Interfaces:**
- Consumes: nothing new — this task only changes how `local` profile populates the `Jwt` principal that Task 4's controller already reads via `@AuthenticationPrincipal Jwt`.

**Context:** today this service's `LocalDevSecurityConfig` is `permitAll()`-only (it never had an endpoint reading `@AuthenticationPrincipal Jwt` before). Without this change, `jwt.getSubject()` in `RepaymentPlanController` NPEs under `local` profile. This copies `applicant-service`'s already-proven stub-filter pattern verbatim (package renamed).

- [ ] **Step 1: Write the failing test**

Create `LocalProfileSecurityIntegrationTest.java`:

```java
package com.bridgepay.repayment;

import com.bridgepay.repayment.domain.RepaymentPlan;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mirrors applicant-service's LocalProfileSecurityIntegrationTest: proves the
 * local-profile stub filter uses the real bearer token's subject instead of
 * collapsing every login onto one fixed demo identity.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(LocalProfileSecurityIntegrationTest.TestConfig.class)
class LocalProfileSecurityIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bridgepay")
            .withUsername("test")
            .withPassword("test");

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:latest"));

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgresContainer() {
            return POSTGRES;
        }

        @Bean
        @ServiceConnection
        KafkaContainer kafkaContainer() {
            return KAFKA;
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RepaymentPlanRepository repaymentPlanRepository;

    @Test
    void get_usesTheRealSubjectFromABearerToken_insteadOfTheFixedDemoSubject() throws Exception {
        UUID applicationId = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        repaymentPlanRepository.save(new RepaymentPlan(applicationId, subject,
                "ctm_1", "txn_1", new BigDecimal("100.00"), 4, new BigDecimal("25.00")));

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId)
                        .header("Authorization", "Bearer " + unsignedTestToken(subject.toString())))
                .andExpect(status().isOk());
    }

    @Test
    void get_returnsForbidden_whenARealBearerTokensSubjectDoesNotOwnThePlan() throws Exception {
        UUID applicationId = UUID.randomUUID();
        repaymentPlanRepository.save(new RepaymentPlan(applicationId, UUID.randomUUID(),
                "ctm_2", "txn_2", new BigDecimal("100.00"), 4, new BigDecimal("25.00")));

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId)
                        .header("Authorization", "Bearer " + unsignedTestToken(UUID.randomUUID().toString())))
                .andExpect(status().isForbidden());
    }

    private static String unsignedTestToken(String subject) {
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url("{\"sub\":\"" + subject + "\"}");
        return header + "." + payload + ".";
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/repayment-reconciliation-service && mvn test -Dtest=LocalProfileSecurityIntegrationTest`
Expected: FAIL — NPE from `jwt.getSubject()` (null principal), surfaced through Spring MVC as a 500

- [ ] **Step 3: Replace `LocalDevSecurityConfig` with the stub-filter version**

Replace the full contents of `LocalDevSecurityConfig.java` with:

```java
package com.bridgepay.repayment.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Convenience-only security config for local docker-compose use without a
 * running Keycloak. Stubs an authenticated Jwt principal on every request so
 * endpoints reading @AuthenticationPrincipal Jwt behave like a real
 * authenticated call instead of NPE-ing on a null principal - permitAll()
 * alone leaves the security context empty, it does not populate one. When a
 * real bearer token is present, its "sub" claim is used as-is, unvalidated -
 * local profile's whole point is to skip signature verification - so
 * different real logins get different identities instead of collapsing onto
 * one fixed demo subject. Falls back to a fixed demo subject when no token is
 * present at all (plain curl testing, etc). Copied verbatim from
 * applicant-service's LocalDevSecurityConfig.
 */
@Configuration
@Profile("local")
public class LocalDevSecurityConfig {

    static final String LOCAL_DEV_SUBJECT = "00000000-0000-7000-8000-000000000099";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new StubJwtAuthenticationFilter(), AuthorizationFilter.class);
        return http.build();
    }

    private static class StubJwtAuthenticationFilter extends OncePerRequestFilter {
        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Jwt stubJwt = Jwt.withTokenValue("local-dev-token")
                    .header("alg", "none")
                    .claim("sub", resolveSubject(request))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(stubJwt, List.of()));
            chain.doFilter(request, response);
        }

        private String resolveSubject(HttpServletRequest request) {
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                return LOCAL_DEV_SUBJECT;
            }
            try {
                String[] parts = header.substring(7).split("\\.");
                byte[] payloadBytes = Base64.getUrlDecoder().decode(padBase64Url(parts[1]));
                JsonNode payload = objectMapper.readTree(payloadBytes);
                String sub = payload.path("sub").asString(null);
                return (sub == null || sub.isBlank()) ? LOCAL_DEV_SUBJECT : sub;
            } catch (Exception malformedToken) {
                return LOCAL_DEV_SUBJECT;
            }
        }

        private static String padBase64Url(String segment) {
            int remainder = segment.length() % 4;
            return remainder == 0 ? segment : segment + "=".repeat(4 - remainder);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd services/repayment-reconciliation-service && mvn test -Dtest=LocalProfileSecurityIntegrationTest`
Expected: PASS

- [ ] **Step 5: Run the full service suite**

Run: `cd services/repayment-reconciliation-service && mvn clean verify`
Expected: PASS, all tests green (confirms the webhook/internal endpoints, which don't read `@AuthenticationPrincipal Jwt`, are unaffected)

- [ ] **Step 6: Commit**

```bash
git add services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/config/LocalDevSecurityConfig.java services/repayment-reconciliation-service/src/test/java/com/bridgepay/repayment/LocalProfileSecurityIntegrationTest.java
git commit -m "repayment-reconciliation-service: stub real JWT subject under local profile"
```

---

### Task 6: api-gateway — route to repayment-reconciliation-service

**Files:**
- Modify: `services/api-gateway/src/main/resources/application.yaml`
- Modify: `docker-compose.yml` (root)
- Test: `services/api-gateway/src/test/java/com/bridgepay/gateway/RoutingIntegrationTest.java`

**Interfaces:**
- Produces: gateway now forwards `/api/v1/repayment-plans/**` to repayment-reconciliation-service, so Task 8's storefront `RepaymentPlans` service can call `GET /api/v1/repayment-plans/{applicationId}` through `localhost:8086` like every other endpoint.

- [ ] **Step 1: Write the failing test**

Add to `RoutingIntegrationTest.java`, and add the new property registration to the existing `registerProperties` method:

```java
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("bridgepay.applicant-service.base-url", wireMock::baseUrl);
        registry.add("bridgepay.application-service.base-url", wireMock::baseUrl);
        registry.add("bridgepay.repayment-reconciliation-service.base-url", wireMock::baseUrl);
    }
```

```java
    @Test
    void routesRepaymentPlanPaths_toRepaymentReconciliationService() throws Exception {
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/api/v1/repayment-plans/plan-1"))
                .willReturn(okJson("{}")));

        mockMvc.perform(get("/api/v1/repayment-plans/plan-1"))
                .andExpect(status().isOk());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/api-gateway && mvn test -Dtest=RoutingIntegrationTest`
Expected: FAIL with 404 (no route matches `/api/v1/repayment-plans/**` yet)

- [ ] **Step 3: Add the route**

In `application.yaml`, add a new route entry after the existing `merchants` route:

```yaml
            - id: repayment-plans
              uri: ${bridgepay.repayment-reconciliation-service.base-url}
              predicates:
                - Path=/api/v1/repayment-plans/**
```

And add the matching base-url property after `application-service`:

```yaml
  repayment-reconciliation-service:
    base-url: ${REPAYMENT_RECONCILIATION_SERVICE_URL:http://localhost:8084}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd services/api-gateway && mvn test -Dtest=RoutingIntegrationTest`
Expected: PASS

- [ ] **Step 5: Run the full gateway suite**

Run: `cd services/api-gateway && mvn clean verify`
Expected: PASS, all tests green

- [ ] **Step 6: Wire the real URL into the root `docker-compose.yml`**

Tests use WireMock and never touch `docker-compose.yml`, so without this step the route works in tests but not in a real `docker-compose up` (the gateway would default to `http://localhost:8084`, which doesn't resolve to the right container). In `docker-compose.yml`'s `api-gateway` service, add `repayment-reconciliation-service` to `depends_on` and the new env var to `environment`:

```yaml
  api-gateway:
    build: ./services/api-gateway
    depends_on:
      - applicant-service
      - application-service
      - repayment-reconciliation-service
    environment:
      SPRING_PROFILES_ACTIVE: local
      APPLICANT_SERVICE_URL: http://applicant-service:8080
      APPLICATION_SERVICE_URL: http://application-service:8081
      REPAYMENT_RECONCILIATION_SERVICE_URL: http://repayment-reconciliation-service:8084
    ports:
      - "8086:8086"
```

- [ ] **Step 7: Commit**

```bash
git add services/api-gateway/src/main/resources/application.yaml services/api-gateway/src/test/java/com/bridgepay/gateway/RoutingIntegrationTest.java docker-compose.yml
git commit -m "api-gateway: route /api/v1/repayment-plans/** to repayment-reconciliation-service"
```

---

### Task 7: storefront — models and HTTP services

**Files:**
- Create: `frontend/storefront/src/app/shared/models/page.ts`
- Modify: `frontend/storefront/src/app/shared/models/application.ts`
- Create: `frontend/storefront/src/app/account/repayment-plan.model.ts`
- Modify: `frontend/storefront/src/app/checkout/applications.ts`
- Modify: `frontend/storefront/src/app/checkout/applications.spec.ts`
- Create: `frontend/storefront/src/app/account/repayment-plans.ts`
- Create: `frontend/storefront/src/app/account/repayment-plans.spec.ts`

**Interfaces:**
- Produces: `Page<T>` interface (`{ content: T[]; totalElements: number; totalPages: number; number: number; size: number }`)
- Produces: extended `ApplicationResponse` interface (adds `applicantId`, `merchantId`, `amount`, `riskScore`, `scoreFactors`, `decisionAt` — matches the backend DTO and `main-app`'s own model of the same shape)
- Produces: `Installment` / `RepaymentPlanResponse` interfaces
- Produces: `Applications.listMine(page = 0, size = 20): Observable<Page<ApplicationResponse>>`
- Produces: `RepaymentPlans.getPlan(applicationId: string): Observable<RepaymentPlanResponse>`
- Consumed by: Task 9 (`InstallmentSchedule`) and Task 10 (`Account`)

- [ ] **Step 1: Create the `Page` model**

```typescript
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}
```

- [ ] **Step 2: Extend the `ApplicationResponse` model**

Replace the full contents of `frontend/storefront/src/app/shared/models/application.ts`:

```typescript
export interface ScoreFactor {
  feature: string;
  contribution: number;
}

export interface ApplicationResponse {
  applicationId: string;
  applicantId: string;
  merchantId: string;
  amount: number;
  status: string;
  riskScore: number | null;
  scoreFactors: ScoreFactor[];
  installmentCount: number | null;
  installmentAmount: number | null;
  decisionAt: string | null;
}
```

- [ ] **Step 3: Create the repayment plan model**

```typescript
export interface Installment {
  sequenceNumber: number;
  dueDate: string;
  amount: number;
  status: string;
  paidAt: string | null;
}

export interface RepaymentPlanResponse {
  planId: string;
  applicationId: string;
  status: string;
  totalAmount: number;
  installmentCount: number;
  installmentAmount: number;
  installments: Installment[];
}
```

- [ ] **Step 4: Write the failing test for `Applications.listMine`**

Add to `applications.spec.ts`:

```typescript
import { Page } from '../shared/models/page';
import { ApplicationResponse } from '../shared/models/application';

  it('fetches the current shopper\'s own applications', () => {
    const page: Page<ApplicationResponse> = {
      content: [],
      totalElements: 0, totalPages: 0, number: 0, size: 20,
    };

    service.listMine().subscribe();

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/applications/me'));
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('20');
    req.flush(page);
  });
```

(Add the two `import` lines at the top of the file alongside the existing imports; add the `it(...)` block inside the existing `describe('Applications', ...)`.)

- [ ] **Step 5: Run test to verify it fails**

Run: `cd frontend/storefront && npx ng test --watch=false`
Expected: FAIL with "service.listMine is not a function"

- [ ] **Step 6: Implement `Applications.listMine`**

Replace the full contents of `frontend/storefront/src/app/checkout/applications.ts`:

```typescript
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { ApplicationResponse } from '../shared/models/application';
import { Page } from '../shared/models/page';

const RIDGELINE_MERCHANT_ID = '00000000-0000-7000-8000-000000000001';

@Injectable({ providedIn: 'root' })
export class Applications {
  private readonly http = inject(HttpClient);

  checkout(amount: number): Observable<ApplicationResponse> {
    return this.http.post<ApplicationResponse>(
      `${environment.gatewayBaseUrl}/api/v1/applications`,
      { merchantId: RIDGELINE_MERCHANT_ID, amount },
      { headers: { 'Idempotency-Key': crypto.randomUUID() } },
    );
  }

  listMine(page = 0, size = 20): Observable<Page<ApplicationResponse>> {
    return this.http.get<Page<ApplicationResponse>>(
      `${environment.gatewayBaseUrl}/api/v1/applications/me`,
      { params: { page, size } },
    );
  }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `cd frontend/storefront && npx ng test --watch=false`
Expected: PASS

- [ ] **Step 8: Write the failing test for `RepaymentPlans`**

Create `frontend/storefront/src/app/account/repayment-plans.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { RepaymentPlans } from './repayment-plans';
import { RepaymentPlanResponse } from './repayment-plan.model';

describe('RepaymentPlans', () => {
  let service: RepaymentPlans;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(RepaymentPlans);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('fetches the repayment plan for a given application', () => {
    const plan: RepaymentPlanResponse = {
      planId: 'plan-1', applicationId: 'app-1', status: 'ACTIVE',
      totalAmount: 200, installmentCount: 4, installmentAmount: 50,
      installments: [],
    };

    service.getPlan('app-1').subscribe();

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/repayment-plans/app-1'));
    expect(req.request.method).toBe('GET');
    req.flush(plan);
  });
});
```

- [ ] **Step 9: Run test to verify it fails**

Run: `cd frontend/storefront && npx ng test --watch=false`
Expected: FAIL with "Cannot find module './repayment-plans'"

- [ ] **Step 10: Implement `RepaymentPlans`**

Create `frontend/storefront/src/app/account/repayment-plans.ts`:

```typescript
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { RepaymentPlanResponse } from './repayment-plan.model';

@Injectable({ providedIn: 'root' })
export class RepaymentPlans {
  private readonly http = inject(HttpClient);

  getPlan(applicationId: string): Observable<RepaymentPlanResponse> {
    return this.http.get<RepaymentPlanResponse>(
      `${environment.gatewayBaseUrl}/api/v1/repayment-plans/${applicationId}`,
    );
  }
}
```

- [ ] **Step 11: Run test to verify it passes**

Run: `cd frontend/storefront && npx ng test --watch=false`
Expected: PASS, full suite green

- [ ] **Step 12: Commit**

```bash
git add frontend/storefront/src/app/shared/models/page.ts frontend/storefront/src/app/shared/models/application.ts frontend/storefront/src/app/account/repayment-plan.model.ts frontend/storefront/src/app/checkout/applications.ts frontend/storefront/src/app/checkout/applications.spec.ts frontend/storefront/src/app/account/repayment-plans.ts frontend/storefront/src/app/account/repayment-plans.spec.ts
git commit -m "storefront: add models and HTTP services for the account view"
```

---

### Task 8: storefront — Angular Router setup, `Shop` extraction, `App` shell

**Files:**
- Create: `frontend/storefront/src/app/app.routes.ts`
- Modify: `frontend/storefront/src/app/app.config.ts`
- Create: `frontend/storefront/src/app/shop/shop.ts` (moved from `app.ts`)
- Create: `frontend/storefront/src/app/shop/shop.html` (moved from `app.html`)
- Create: `frontend/storefront/src/app/shop/shop.css` (moved from `app.css`, empty)
- Create: `frontend/storefront/src/app/shop/shop.spec.ts` (moved from `app.spec.ts`)
- Modify: `frontend/storefront/src/app/app.ts` (becomes a thin shell)
- Modify: `frontend/storefront/src/app/app.html` (becomes a thin shell)
- Modify: `frontend/storefront/src/app/app.spec.ts` (new, small test set)

**Interfaces:**
- Produces: `export const routes: Routes` in `app.routes.ts`, with `{ path: '', component: Shop }` — Task 10 adds the `{ path: 'account', component: Account }` entry to this same array.
- Produces: `Shop` — identical behavior to today's `App` (catalog/signup/confirm/result step machine), just renamed and relocated.
- Produces: `App` — thin shell with a top bar (My Account link + Sign out when authenticated) and a `<router-outlet>`.

This task is a same-behavior extraction (no new user-facing logic in `Shop` itself) plus a new thin `App` shell — there's no new business logic to drive with a single failing test the way earlier tasks had, so steps here move the file and then verify via the full existing suite (which must still pass unchanged) before adding the new shell test.

- [ ] **Step 1: Move `App`'s content into `Shop`, unchanged except naming**

Create `frontend/storefront/src/app/shop/shop.html` (identical to current `app.html`, minus the "Sign out" button which moves to the shell in Step 4):

```html
<div class="min-h-screen p-6 sm:p-10 max-w-3xl mx-auto">
  @if (step() !== 'catalog') {
    <div class="mb-6 text-sm">
      <button (click)="keepShopping()" class="text-widget-accent underline">← Keep shopping</button>
    </div>
  }

  @switch (step()) {
    @case ('catalog') {
      <app-product-catalog (payInFour)="onPayInFour($event)" />
    }
    @case ('signup') {
      <app-signup-form (signedUp)="onSignedUp()" />
    }
    @case ('confirm') {
      @if (selectedProduct(); as product) {
        <app-checkout-confirm [product]="product" (decided)="onDecided($event)" />
      }
    }
    @case ('result') {
      @if (result(); as response) {
        <app-checkout-result [response]="response" (backToShop)="backToShop()" />
      }
    }
  }
</div>
```

Create `frontend/storefront/src/app/shop/shop.css` (empty file, matching the current `app.css`).

Create `frontend/storefront/src/app/shop/shop.ts` (identical to current `app.ts`, class renamed `App` → `Shop`, selector `app-root` → `app-shop`):

```typescript
import { Component, inject, signal } from '@angular/core';
import { Auth } from '../core/auth';
import { Applicants } from '../signup/applicants';
import { ProductCatalog } from '../catalog/product-catalog/product-catalog';
import { SignupForm } from '../signup/signup-form/signup-form';
import { CheckoutConfirm } from '../checkout/checkout-confirm/checkout-confirm';
import { CheckoutResult } from '../checkout/checkout-result/checkout-result';
import { PRODUCTS, Product } from '../catalog/products';
import { ApplicationResponse } from '../shared/models/application';

type Step = 'catalog' | 'signup' | 'confirm' | 'result';

const PENDING_PRODUCT_KEY = 'storefront.pendingProductId';

@Component({
  selector: 'app-shop',
  imports: [ProductCatalog, SignupForm, CheckoutConfirm, CheckoutResult],
  templateUrl: './shop.html',
  styleUrl: './shop.css',
})
export class Shop {
  protected readonly auth = inject(Auth);
  private readonly applicants = inject(Applicants);

  protected readonly step = signal<Step>('catalog');
  protected readonly selectedProduct = signal<Product | null>(null);
  protected readonly result = signal<ApplicationResponse | null>(null);

  constructor() {
    const pendingId = sessionStorage.getItem(PENDING_PRODUCT_KEY);
    if (pendingId && this.auth.authenticated()) {
      const product = PRODUCTS.find((p) => p.id === pendingId);
      if (product) {
        this.selectedProduct.set(product);
        this.resumeAfterLogin();
      } else {
        sessionStorage.removeItem(PENDING_PRODUCT_KEY);
      }
    }
  }

  private resumeAfterLogin(): void {
    this.applicants.getMyProfile().subscribe({
      next: () => this.step.set('confirm'),
      error: () => this.step.set('signup'),
    });
  }

  protected onPayInFour(productId: string): void {
    const product = PRODUCTS.find((p) => p.id === productId);
    if (!product) return;
    this.selectedProduct.set(product);
    sessionStorage.setItem(PENDING_PRODUCT_KEY, productId);
    if (this.auth.authenticated()) {
      this.resumeAfterLogin();
    } else {
      this.auth.login(`${window.location.origin}/`);
    }
  }

  protected onSignedUp(): void {
    this.step.set('confirm');
  }

  protected onDecided(response: ApplicationResponse): void {
    sessionStorage.removeItem(PENDING_PRODUCT_KEY);
    this.result.set(response);
    this.step.set('result');
  }

  protected keepShopping(): void {
    sessionStorage.removeItem(PENDING_PRODUCT_KEY);
    this.step.set('catalog');
    this.selectedProduct.set(null);
    this.result.set(null);
  }

  protected backToShop(): void {
    this.keepShopping();
  }
}
```

Create `frontend/storefront/src/app/shop/shop.spec.ts` (identical to current `app.spec.ts`, `App` → `Shop`, import path updated for the new directory depth):

```typescript
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { Shop } from './shop';
import { Auth } from '../core/auth';
import { Applicants } from '../signup/applicants';

const PENDING_KEY = 'storefront.pendingProductId';

describe('Shop', () => {
  afterEach(() => sessionStorage.removeItem(PENDING_KEY));

  function setup(authenticated: boolean, getMyProfile: () => any, login = () => {}) {
    TestBed.configureTestingModule({
      imports: [Shop],
      providers: [
        { provide: Auth, useValue: { authenticated: () => authenticated, login, logout: () => {} } },
        { provide: Applicants, useValue: { getMyProfile, signUp: () => of({}) } },
      ],
    });
    return TestBed.createComponent(Shop);
  }

  it('triggers login and stays on the catalog step when Pay in 4 is clicked while unauthenticated', () => {
    let loginCalled = false;
    const fixture = setup(false, () => of({}), () => (loginCalled = true));
    const shop = fixture.componentInstance as any;

    shop.onPayInFour('basin-rain-jacket');

    expect(loginCalled).toBe(true);
    expect(shop.step()).toBe('catalog');
  });

  it('moves to confirm when Pay in 4 is clicked while authenticated and a profile already exists', () => {
    const fixture = setup(true, () => of({ id: 'a-1' }));
    const shop = fixture.componentInstance as any;

    shop.onPayInFour('basin-rain-jacket');

    expect(shop.step()).toBe('confirm');
  });

  it('moves to signup when Pay in 4 is clicked while authenticated but no profile exists yet', () => {
    const fixture = setup(true, () => throwError(() => new Error('404')));
    const shop = fixture.componentInstance as any;

    shop.onPayInFour('basin-rain-jacket');

    expect(shop.step()).toBe('signup');
  });

  it('resumes automatically on construction when a pending checkout exists and the user is already authenticated', () => {
    sessionStorage.setItem(PENDING_KEY, 'basin-rain-jacket');
    const fixture = setup(true, () => of({ id: 'a-1' }));

    expect((fixture.componentInstance as any).step()).toBe('confirm');
  });

  it('moves to result and clears the pending checkout when a decision is made', () => {
    const fixture = setup(true, () => of({ id: 'a-1' }));
    const shop = fixture.componentInstance as any;
    shop.onPayInFour('basin-rain-jacket');

    shop.onDecided({ applicationId: 'app-1', status: 'APPROVED', installmentCount: 4, installmentAmount: 50 });

    expect(shop.step()).toBe('result');
    expect(sessionStorage.getItem(PENDING_KEY)).toBeNull();
  });

  it('moves to confirm when signup completes', () => {
    const fixture = setup(true, () => of({ id: 'a-1' }));
    const shop = fixture.componentInstance as any;

    shop.onSignedUp();

    expect(shop.step()).toBe('confirm');
  });

  it('keepShopping resets to catalog and clears the pending checkout', () => {
    sessionStorage.setItem(PENDING_KEY, 'basin-rain-jacket');
    const fixture = setup(true, () => of({ id: 'a-1' }));
    const shop = fixture.componentInstance as any;

    shop.keepShopping();

    expect(shop.step()).toBe('catalog');
    expect(sessionStorage.getItem(PENDING_KEY)).toBeNull();
  });

  it('backToShop behaves the same as keepShopping', () => {
    const fixture = setup(true, () => of({ id: 'a-1' }));
    const shop = fixture.componentInstance as any;
    shop.onPayInFour('basin-rain-jacket');
    shop.onDecided({ applicationId: 'app-1', status: 'DECLINED', installmentCount: null, installmentAmount: null });

    shop.backToShop();

    expect(shop.step()).toBe('catalog');
    expect(sessionStorage.getItem(PENDING_KEY)).toBeNull();
  });

  it('does not resume automatically when a pending checkout exists but the user is not authenticated', () => {
    sessionStorage.setItem(PENDING_KEY, 'basin-rain-jacket');
    const fixture = setup(false, () => of({}));

    expect((fixture.componentInstance as any).step()).toBe('catalog');
  });

  it('renders the product catalog on the catalog step', () => {
    const fixture = setup(true, () => of({ id: 'a-1' }));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-product-catalog')).toBeTruthy();
  });

  it('renders the checkout confirmation on the confirm step', () => {
    const fixture = setup(true, () => of({ id: 'a-1' }));
    const shop = fixture.componentInstance as any;
    shop.onPayInFour('basin-rain-jacket');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-checkout-confirm')).toBeTruthy();
  });
});
```

Delete the old `frontend/storefront/src/app/app.spec.ts` content (it's replaced by Step 4's new shell test, and its logic now lives in `shop.spec.ts` above).

- [ ] **Step 2: Create the routes file**

```typescript
import { Routes } from '@angular/router';
import { Shop } from './shop/shop';

export const routes: Routes = [
  { path: '', component: Shop },
];
```

- [ ] **Step 3: Wire `provideRouter` into `app.config.ts`**

In `frontend/storefront/src/app/app.config.ts`, add this import:

```typescript
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
```

Add `provideRouter(routes)` to the `providers` array (alongside the existing `provideBrowserGlobalErrorListeners()` etc.).

- [ ] **Step 4: Replace `App` with the thin shell**

Replace the full contents of `frontend/storefront/src/app/app.html`:

```html
<div class="max-w-3xl mx-auto px-6 sm:px-10 pt-4 flex justify-end items-center gap-4 text-sm">
  @if (auth.authenticated()) {
    <a routerLink="/account" class="text-widget-accent underline">My Account</a>
    <button (click)="auth.logout()" class="text-ink-muted underline">Sign out</button>
  }
</div>
<router-outlet />
```

Replace the full contents of `frontend/storefront/src/app/app.ts`:

```typescript
import { Component, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { Auth } from './core/auth';

@Component({
  selector: 'app-root',
  imports: [RouterLink, RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly auth = inject(Auth);
}
```

Replace the full contents of `frontend/storefront/src/app/app.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { Auth } from './core/auth';

describe('App', () => {
  function setup(authenticated: boolean) {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
        { provide: Auth, useValue: { authenticated: () => authenticated, logout: () => {} } },
      ],
    });
    return TestBed.createComponent(App);
  }

  it('shows no account/sign-out links when not authenticated', () => {
    const fixture = setup(false);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('My Account');
    expect(text).not.toContain('Sign out');
  });

  it('shows My Account and Sign out links when authenticated', () => {
    const fixture = setup(true);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('My Account');
    expect(text).toContain('Sign out');
  });
});
```

- [ ] **Step 5: Run the full suite to verify everything still passes**

Run: `cd frontend/storefront && npx ng test --watch=false`
Expected: PASS — `shop.spec.ts` covers what `app.spec.ts` used to, and the new `app.spec.ts` covers the shell

- [ ] **Step 6: Commit**

```bash
git add frontend/storefront/src/app/app.routes.ts frontend/storefront/src/app/app.config.ts frontend/storefront/src/app/shop frontend/storefront/src/app/app.ts frontend/storefront/src/app/app.html frontend/storefront/src/app/app.spec.ts
git commit -m "storefront: add Angular Router, extract Shop from the App shell"
```

(`app.css` itself is untouched — it stays the same empty file, still referenced by the new shell's `styleUrl` — so it needs no `git add`.)

---

### Task 9: storefront — `InstallmentSchedule` component

**Files:**
- Create: `frontend/storefront/src/app/account/installment-schedule/installment-schedule.ts`
- Create: `frontend/storefront/src/app/account/installment-schedule/installment-schedule.html`
- Create: `frontend/storefront/src/app/account/installment-schedule/installment-schedule.css` (empty)
- Create: `frontend/storefront/src/app/account/installment-schedule/installment-schedule.spec.ts`

**Interfaces:**
- Consumes: `RepaymentPlans.getPlan(applicationId)` from Task 7
- Produces: `InstallmentSchedule` component, `selector: 'app-installment-schedule'`, `applicationId = input.required<string>()` — consumed by Task 10's `Account` component.

- [ ] **Step 1: Write the failing test**

Create `installment-schedule.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { InstallmentSchedule } from './installment-schedule';
import { RepaymentPlans } from '../repayment-plans';
import { RepaymentPlanResponse } from '../repayment-plan.model';

describe('InstallmentSchedule', () => {
  it('renders a row per installment once the plan loads', () => {
    const plan: RepaymentPlanResponse = {
      planId: 'plan-1', applicationId: 'app-1', status: 'ACTIVE',
      totalAmount: 200, installmentCount: 2, installmentAmount: 100,
      installments: [
        { sequenceNumber: 1, dueDate: '2026-01-01', amount: 100, status: 'PAID', paidAt: '2026-01-01T00:00:00Z' },
        { sequenceNumber: 2, dueDate: '2026-01-08', amount: 100, status: 'SCHEDULED', paidAt: null },
      ],
    };

    TestBed.configureTestingModule({
      imports: [InstallmentSchedule],
      providers: [{ provide: RepaymentPlans, useValue: { getPlan: () => of(plan) } }],
    });

    const fixture = TestBed.createComponent(InstallmentSchedule);
    fixture.componentRef.setInput('applicationId', 'app-1');
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('PAID');
    expect(text).toContain('SCHEDULED');
  });

  it('shows a distinct error message when the plan fails to load', () => {
    TestBed.configureTestingModule({
      imports: [InstallmentSchedule],
      providers: [{ provide: RepaymentPlans, useValue: { getPlan: () => throwError(() => new Error('404')) } }],
    });

    const fixture = TestBed.createComponent(InstallmentSchedule);
    fixture.componentRef.setInput('applicationId', 'app-1');
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Could not load the payment schedule',
    );
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend/storefront && npx ng test --watch=false`
Expected: FAIL with "Cannot find module './installment-schedule'"

- [ ] **Step 3: Implement the component**

Create `installment-schedule.html`:

```html
@if (loadError()) {
  <p class="text-sm text-red-600 mt-2">Could not load the payment schedule.</p>
} @else if (plan(); as p) {
  <table class="w-full text-sm mt-3">
    <thead>
      <tr class="text-left text-ink-muted">
        <th>Due</th>
        <th>Amount</th>
        <th>Status</th>
      </tr>
    </thead>
    <tbody>
      @for (installment of p.installments; track installment.sequenceNumber) {
        <tr>
          <td>{{ installment.dueDate | date: 'mediumDate' }}</td>
          <td>${{ installment.amount | number: '1.2-2' }}</td>
          <td>{{ installment.status }}</td>
        </tr>
      }
    </tbody>
  </table>
}
```

Create `installment-schedule.ts`:

```typescript
import { Component, inject, input, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe } from '@angular/common';
import { catchError, of, switchMap } from 'rxjs';
import { RepaymentPlans } from '../repayment-plans';
import { RepaymentPlanResponse } from '../repayment-plan.model';

@Component({
  selector: 'app-installment-schedule',
  imports: [DatePipe, DecimalPipe],
  templateUrl: './installment-schedule.html',
  styleUrl: './installment-schedule.css',
})
export class InstallmentSchedule {
  applicationId = input.required<string>();

  private readonly repaymentPlans = inject(RepaymentPlans);

  protected readonly loadError = signal(false);

  protected readonly plan = toSignal(
    toObservable(this.applicationId).pipe(
      switchMap((id) =>
        this.repaymentPlans.getPlan(id).pipe(
          catchError(() => {
            this.loadError.set(true);
            return of(null);
          }),
        ),
      ),
    ),
    { initialValue: null as RepaymentPlanResponse | null },
  );
}
```

Create `installment-schedule.css` (empty file).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend/storefront && npx ng test --watch=false`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/storefront/src/app/account/installment-schedule
git commit -m "storefront: add InstallmentSchedule component"
```

---

### Task 10: storefront — `Account` component and `/account` route

**Files:**
- Create: `frontend/storefront/src/app/account/account.ts`
- Create: `frontend/storefront/src/app/account/account.html`
- Create: `frontend/storefront/src/app/account/account.css` (empty)
- Create: `frontend/storefront/src/app/account/account.spec.ts`
- Modify: `frontend/storefront/src/app/app.routes.ts`

**Interfaces:**
- Consumes: `Applications.listMine()` (Task 7), `Auth.authenticated()`/`Auth.login()` (existing), `InstallmentSchedule` (Task 9)
- Produces: `Account` component mounted at `/account`, completing the feature.

- [ ] **Step 1: Write the failing tests**

Create `account.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { Account } from './account';
import { Auth } from '../core/auth';
import { Applications } from '../checkout/applications';
import { Page } from '../shared/models/page';
import { ApplicationResponse } from '../shared/models/application';

describe('Account', () => {
  function setup(authenticated: boolean, listMine: () => any, login = () => {}) {
    TestBed.configureTestingModule({
      imports: [Account],
      providers: [
        { provide: Auth, useValue: { authenticated: () => authenticated, login } },
        { provide: Applications, useValue: { listMine } },
      ],
    });
    return TestBed.createComponent(Account);
  }

  it('triggers login when not authenticated', () => {
    let loginCalled = false;
    setup(false, () => of({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }), () => (loginCalled = true));

    expect(loginCalled).toBe(true);
  });

  it('shows an empty-state message when there are no applications', () => {
    const emptyPage: Page<ApplicationResponse> = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };
    const fixture = setup(true, () => of(emptyPage));
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No purchases yet');
  });

  it('shows a distinct error message instead of the empty state when the request fails', () => {
    const fixture = setup(true, () => throwError(() => new Error('500')));
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Could not load your purchases');
    expect(text).not.toContain('No purchases yet');
  });

  it('renders a row per application, with an expand button only for approved ones', () => {
    const page: Page<ApplicationResponse> = {
      content: [
        {
          applicationId: 'app-1', applicantId: 'a-1', merchantId: 'm-1', amount: 200,
          status: 'APPROVED', riskScore: 0.1, scoreFactors: [],
          installmentCount: 4, installmentAmount: 50, decisionAt: '2026-09-12T00:00:00Z',
        },
        {
          applicationId: 'app-2', applicantId: 'a-1', merchantId: 'm-1', amount: 50,
          status: 'DECLINED', riskScore: 0.9, scoreFactors: [],
          installmentCount: null, installmentAmount: null, decisionAt: '2026-09-13T00:00:00Z',
        },
      ],
      totalElements: 2, totalPages: 1, number: 0, size: 20,
    };
    const fixture = setup(true, () => of(page));
    fixture.detectChanges();

    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll('button');
    expect(buttons.length).toBe(1);
  });

  it('toggles the installment schedule when the expand button is clicked', () => {
    const page: Page<ApplicationResponse> = {
      content: [
        {
          applicationId: 'app-1', applicantId: 'a-1', merchantId: 'm-1', amount: 200,
          status: 'APPROVED', riskScore: 0.1, scoreFactors: [],
          installmentCount: 4, installmentAmount: 50, decisionAt: '2026-09-12T00:00:00Z',
        },
      ],
      totalElements: 1, totalPages: 1, number: 0, size: 20,
    };
    const fixture = setup(true, () => of(page));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-installment-schedule')).toBeFalsy();

    (fixture.nativeElement as HTMLElement).querySelector('button')!.dispatchEvent(new Event('click'));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-installment-schedule')).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend/storefront && npx ng test --watch=false`
Expected: FAIL with "Cannot find module './account'"

- [ ] **Step 3: Implement the component**

Create `account.html`:

```html
<div class="min-h-screen p-6 sm:p-10 max-w-3xl mx-auto">
  <h1 class="text-lg font-semibold mb-6">My Account</h1>

  @if (loadError()) {
    <p class="text-sm text-red-600">Could not load your purchases.</p>
  } @else if (rows().length === 0) {
    <p class="text-sm text-ink-muted">No purchases yet.</p>
  } @else {
    <ul class="space-y-3">
      @for (app of rows(); track app.applicationId) {
        <li class="rounded-xl bg-widget-surface p-4">
          <div class="flex justify-between items-center gap-4">
            <span>{{ app.status }}</span>
            <span>${{ app.amount | number: '1.2-2' }}</span>
            @if (app.status === 'APPROVED') {
              <button (click)="toggle(app.applicationId)" class="text-widget-accent underline text-sm">
                {{ expandedId() === app.applicationId ? 'Hide schedule' : 'View schedule' }}
              </button>
            }
          </div>
          @if (expandedId() === app.applicationId) {
            <app-installment-schedule [applicationId]="app.applicationId" />
          }
        </li>
      }
    </ul>
  }
</div>
```

Create `account.ts`:

```typescript
import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { catchError, map, of } from 'rxjs';
import { Auth } from '../core/auth';
import { Applications } from '../checkout/applications';
import { ApplicationResponse } from '../shared/models/application';
import { InstallmentSchedule } from './installment-schedule/installment-schedule';

@Component({
  selector: 'app-account',
  imports: [DecimalPipe, InstallmentSchedule],
  templateUrl: './account.html',
  styleUrl: './account.css',
})
export class Account {
  private readonly auth = inject(Auth);
  private readonly applicationsService = inject(Applications);

  protected readonly loadError = signal(false);
  protected readonly expandedId = signal<string | null>(null);

  constructor() {
    if (!this.auth.authenticated()) {
      this.auth.login(`${window.location.origin}/account`);
    }
  }

  protected readonly rows = toSignal(
    this.applicationsService.listMine().pipe(
      map((page) => page.content),
      catchError(() => {
        this.loadError.set(true);
        return of([] as ApplicationResponse[]);
      }),
    ),
    { initialValue: [] as ApplicationResponse[] },
  );

  protected toggle(applicationId: string): void {
    this.expandedId.set(this.expandedId() === applicationId ? null : applicationId);
  }
}
```

Create `account.css` (empty file).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend/storefront && npx ng test --watch=false`
Expected: PASS

- [ ] **Step 5: Wire the route**

Replace the full contents of `frontend/storefront/src/app/app.routes.ts`:

```typescript
import { Routes } from '@angular/router';
import { Shop } from './shop/shop';
import { Account } from './account/account';

export const routes: Routes = [
  { path: '', component: Shop },
  { path: 'account', component: Account },
];
```

- [ ] **Step 6: Run the full suite**

Run: `cd frontend/storefront && npx ng test --watch=false`
Expected: PASS, full suite green

- [ ] **Step 7: Commit**

```bash
git add frontend/storefront/src/app/account/account.ts frontend/storefront/src/app/account/account.html frontend/storefront/src/app/account/account.css frontend/storefront/src/app/account/account.spec.ts frontend/storefront/src/app/app.routes.ts
git commit -m "storefront: add Account component and wire the /account route"
```

---

## After Task 10

Not part of TDD/automated verification, consistent with this project's existing pattern of a human doing the final browser click-through (see `docs/PROGRESS.md`'s "First real end-to-end browser verification" entry): once all 10 tasks are merged, a real `docker-compose up --build` run followed by logging into the storefront as `shopper1` and visiting `/account` is the way to confirm the feature actually renders and behaves correctly end-to-end — no browser tool is available in this environment to do that automatically.
