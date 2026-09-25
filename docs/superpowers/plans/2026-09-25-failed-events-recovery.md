# Failed Events Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop Repayment Reconciliation from silently dropping `applications.approved` events that exhaust their retries: persist them to a `failed_events` table, expose an ops-only list/retry API, and give ops a `main-app` page to see and retry them.

**Architecture:** The Kafka `DefaultErrorHandler` recoverer saves a `FailedEvent` row. `FailedEventService.retry` replays the stored payload in-process through the existing (idempotent) `ApplicationEventConsumer.onApproved`. `FailedEventController` serves `/api/v1/ops/failed-events`, routed by the gateway. Repayment Reconciliation's `local` security config gets parity with Application Service's so `@PreAuthorize` is actually enforced under docker-compose.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Kafka, JPA/Flyway, Testcontainers (Postgres + Kafka), MockMvc; Spring Cloud Gateway WebMVC + WireMock; Angular 21 + Vitest.

**Spec:** `docs/superpowers/specs/2026-09-25-failed-events-recovery-design.md`

## Global Constraints

- UUIDv7 primary keys via `UuidCreator.getTimeOrderedEpoch()`; never `UUID.randomUUID()` in main code.
- `@Version` on `failed_events` (retry can race).
- JPA auditing (`@CreatedDate`/`@LastModifiedDate`, `@EntityListeners(AuditingEntityListener.class)`).
- Error shape `{error, message, traceId, timestamp}` via this service's existing `GlobalExceptionHandler`.
- Ops check is `@PreAuthorize("hasRole('OPS')")` (roles map to `ROLE_<UPPER>`).
- Kafka retry policy unchanged: `FixedBackOff(1000L, 3)`; offset still commits after recovery.
- Scope is Repayment Reconciliation's `applications.approved` consumer only; Notifications Service untouched.
- Only `applications.approved` rows are retryable; others → `409`.
- List endpoint never returns `payload`.
- Backend tests use real Testcontainers Postgres + Kafka (no mocked DB).

## Review Focus

- `ops/failed-events` must be declared **before** `ops/:id` in `app.routes.ts`, or the review-detail route swallows it as an id. (Task 5 route test.)
- Under the `local` profile, a request with **no** bearer token must get `403` from the ops endpoints — not `200` (method security actually on). (Task 3 test.)
- A retry that fails again must return `200` with the row still `FAILED`, `attempts` incremented and the new error — not a 500, and never `RESOLVED`. (Task 2 test.)
- A record whose save to `failed_events` itself fails must not wedge the partition — the recoverer swallows and logs. (Code-level in Task 1; not exercised by a test — reviewer should check the try/catch.)
- Unknown `?status=` → `400 VALIDATION_ERROR`, not `500`. (Task 2 test.)

---

### Task 1: Capture exhausted records into `failed_events`

**Files:**
- Create: `services/repayment-reconciliation-service/src/main/resources/db/migration/V2__create_failed_events.sql`
- Create: `services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/domain/FailedEventStatus.java`
- Create: `services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/domain/FailedEvent.java`
- Create: `services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/repository/FailedEventRepository.java`
- Modify: `services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/config/KafkaConsumerConfig.java`
- Test: `services/repayment-reconciliation-service/src/test/java/com/bridgepay/repayment/web/FailedEventsIntegrationTest.java`

**Interfaces:**
- Produces: `FailedEvent(String topic, String messageKey, String payload, String errorMessage)`; getters `getId() UUID`, `getTopic()`, `getMessageKey()`, `getPayload()`, `getErrorMessage()`, `getStatus() FailedEventStatus`, `getAttempts() int`, `getCreatedAt() Instant`, `getUpdatedAt() Instant`; `markResolved()`; `recordFailedRetry(String errorMessage)`; `static String describe(Throwable)`. `FailedEventStatus { FAILED, RESOLVED }`. `FailedEventRepository extends JpaRepository<FailedEvent, UUID>` with `Page<FailedEvent> findAllByOrderByCreatedAtDesc(Pageable)` and `Page<FailedEvent> findByStatusOrderByCreatedAtDesc(FailedEventStatus, Pageable)`.

- [ ] **Step 1: Write the failing integration test**

```java
package com.bridgepay.repayment.web;

import com.bridgepay.repayment.client.ApplicantClient;
import com.bridgepay.repayment.client.ApplicantProfile;
import com.bridgepay.repayment.client.PaddleClient;
import com.bridgepay.repayment.client.PaddleTransactionResult;
import com.bridgepay.repayment.client.PaddleUnavailableException;
import com.bridgepay.repayment.domain.FailedEvent;
import com.bridgepay.repayment.domain.FailedEventStatus;
import com.bridgepay.repayment.event.ApplicationEvents;
import com.bridgepay.repayment.event.EventEnvelope;
import com.bridgepay.repayment.repository.FailedEventRepository;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import com.github.f4b6a3.uuid.UuidCreator;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Real Postgres + Kafka via Testcontainers. PaddleClient is a switchable fake
 * so a test can make plan creation fail (to exhaust Kafka retries) and then
 * succeed (to prove a manual retry resolves the row).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FailedEventsIntegrationTest.TestConfig.class)
class FailedEventsIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bridgepay")
            .withUsername("test")
            .withPassword("test");

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:latest"));

    static final SwitchablePaddleClient PADDLE = new SwitchablePaddleClient();

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
        @Primary
        ApplicantClient fakeApplicantClient() {
            return new ApplicantClient() {
                @Override
                public ApplicantProfile fetchProfile(UUID applicantId) {
                    return new ApplicantProfile(applicantId, "Ana", "Doe", "ana-" + applicantId + "@example.com", "ctm_fixed");
                }

                @Override
                public void setPaddleCustomerId(UUID applicantId, String paddleCustomerId) {
                }
            };
        }

        @Bean
        @Primary
        PaddleClient fakePaddleClient() {
            return PADDLE;
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

    static class SwitchablePaddleClient implements PaddleClient {
        final AtomicBoolean failing = new AtomicBoolean(false);
        final AtomicInteger sequence = new AtomicInteger();

        @Override
        public String findOrCreateCustomer(String email, String name) {
            return "ctm_fixed";
        }

        @Override
        public PaddleTransactionResult createInstallmentTransaction(String customerId, BigDecimal installmentAmount) {
            if (failing.get()) {
                throw new PaddleUnavailableException("paddle down", null);
            }
            String id = "txn_fake_" + sequence.incrementAndGet();
            return new PaddleTransactionResult(id, "https://sandbox.paddle.com/checkout/" + id);
        }

        @Override
        public void cancelSubscription(String subscriptionId) {
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    FailedEventRepository failedEventRepository;
    @Autowired
    RepaymentPlanRepository repaymentPlanRepository;

    @BeforeEach
    void resetPaddle() {
        PADDLE.failing.set(false);
    }

    void publish(String key, String value) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>("applications.approved", key, value));
        }
    }

    String approvedEnvelope(UUID applicationId) {
        var envelope = new EventEnvelope<>(UuidCreator.getTimeOrderedEpoch(), "application.approved", Instant.now(),
                applicationId, 1, new ApplicationEvents.Approved(UuidCreator.getTimeOrderedEpoch(),
                        UuidCreator.getTimeOrderedEpoch(), new BigDecimal("200.00"), 4, new BigDecimal("50.00")));
        return objectMapper.writeValueAsString(envelope);
    }

    /** Publishes an approved event while Paddle is down and waits for its failed_events row. */
    FailedEvent failedEventFor(UUID applicationId, String json) {
        PADDLE.failing.set(true);
        publish(applicationId.toString(), json);
        return await().atMost(Duration.ofSeconds(30)).until(
                () -> failedEventRepository.findAll().stream()
                        .filter(e -> applicationId.toString().equals(e.getMessageKey()))
                        .findFirst().orElse(null),
                e -> e != null);
    }

    @Test
    void approvedEventThatKeepsFailing_isRecordedAsAFailedEvent() {
        UUID applicationId = UuidCreator.getTimeOrderedEpoch();
        String json = approvedEnvelope(applicationId);

        FailedEvent row = failedEventFor(applicationId, json);

        assertThat(row.getTopic()).isEqualTo("applications.approved");
        assertThat(row.getStatus()).isEqualTo(FailedEventStatus.FAILED);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getErrorMessage()).contains("paddle down");
        assertThat(row.getPayload()).isEqualTo(json);
        assertThat(repaymentPlanRepository.findByApplicationId(applicationId)).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd services/repayment-reconciliation-service && mvn test -Dtest=FailedEventsIntegrationTest`
Expected: compilation FAIL — `FailedEvent`, `FailedEventStatus`, `FailedEventRepository` not found.

- [ ] **Step 3: Migration** — `V2__create_failed_events.sql`:

```sql
CREATE TABLE repayment.failed_events (
    id            UUID PRIMARY KEY,
    topic         VARCHAR(100) NOT NULL,
    message_key   VARCHAR(100),
    payload       TEXT         NOT NULL,
    error_message TEXT         NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    attempts      INT          NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_failed_events_status_created ON repayment.failed_events (status, created_at DESC);
```

- [ ] **Step 4: Status enum** — `FailedEventStatus.java`:

```java
package com.bridgepay.repayment.domain;

public enum FailedEventStatus {
    FAILED,
    RESOLVED
}
```

- [ ] **Step 5: Entity** — `FailedEvent.java`:

```java
package com.bridgepay.repayment.domain;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * A Kafka record whose listener still failed after the error handler's
 * retries were exhausted - the database-backed dead-letter store ops can
 * list and retry (see FailedEventService). payload is the raw record value,
 * replayed verbatim on retry.
 */
@Entity
@Table(name = "failed_events", schema = "repayment")
@EntityListeners(AuditingEntityListener.class)
public class FailedEvent {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    @Column(name = "message_key", length = 100)
    private String messageKey;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "error_message", nullable = false, columnDefinition = "TEXT")
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private FailedEventStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FailedEvent() {
        // required by JPA
    }

    public FailedEvent(String topic, String messageKey, String payload, String errorMessage) {
        this.id = UuidCreator.getTimeOrderedEpoch();
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.errorMessage = errorMessage;
        this.status = FailedEventStatus.FAILED;
        this.attempts = 1;
    }

    /** The most specific cause's message - listener exceptions arrive wrapped by Spring Kafka. */
    public static String describe(Throwable ex) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(ex);
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }

    public void markResolved() {
        this.status = FailedEventStatus.RESOLVED;
    }

    public void recordFailedRetry(String errorMessage) {
        this.attempts++;
        this.errorMessage = errorMessage;
    }

    public UUID getId() {
        return id;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public FailedEventStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
```

- [ ] **Step 6: Repository** — `FailedEventRepository.java`:

```java
package com.bridgepay.repayment.repository;

import com.bridgepay.repayment.domain.FailedEvent;
import com.bridgepay.repayment.domain.FailedEventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FailedEventRepository extends JpaRepository<FailedEvent, UUID> {
    Page<FailedEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<FailedEvent> findByStatusOrderByCreatedAtDesc(FailedEventStatus status, Pageable pageable);
}
```

- [ ] **Step 7: Recoverer** — replace `KafkaConsumerConfig` body (keep package/imports style; add imports for `FailedEvent`, `FailedEventRepository`):

```java
/**
 * 3 retries / 1s backoff, then the record is saved to failed_events (see
 * FailedEvent) so ops can see and retry it from main-app, and the offset
 * commits so the partition keeps moving. If saving the row itself fails, the
 * record is only logged - no worse than before failed_events existed.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler(FailedEventRepository failedEventRepository) {
        return new DefaultErrorHandler((record, ex) -> recover(failedEventRepository, record, ex),
                new FixedBackOff(1000L, 3));
    }

    private void recover(FailedEventRepository failedEventRepository, ConsumerRecord<?, ?> record, Exception ex) {
        log.error("Giving up on record from topic {} partition {} offset {} after retries exhausted: {}",
                record.topic(), record.partition(), record.offset(), ex.getMessage(), ex);
        try {
            failedEventRepository.save(new FailedEvent(record.topic(),
                    record.key() == null ? null : record.key().toString(),
                    String.valueOf(record.value()), FailedEvent.describe(ex)));
        } catch (Exception saveFailure) {
            log.error("Could not record failed event from topic {} partition {} offset {}",
                    record.topic(), record.partition(), record.offset(), saveFailure);
        }
    }
}
```

- [ ] **Step 8: Run to verify it passes**

Run: `cd services/repayment-reconciliation-service && mvn test -Dtest=FailedEventsIntegrationTest`
Expected: PASS, 1 test (takes ~5–10s for 3 retries).

- [ ] **Step 9: Commit**

```bash
git add services/repayment-reconciliation-service
git commit -m "repayment-reconciliation: save exhausted Kafka records to failed_events"
```

---

### Task 2: Ops list/retry API

**Files:**
- Create: `services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/dto/FailedEventResponse.java`
- Create: `services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/service/FailedEventService.java`
- Create: `services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/web/FailedEventController.java`
- Modify: `services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/web/GlobalExceptionHandler.java`
- Test: `services/repayment-reconciliation-service/src/test/java/com/bridgepay/repayment/web/FailedEventsIntegrationTest.java` (extend)

**Interfaces:**
- Consumes: Task 1's `FailedEvent`, `FailedEventStatus`, `FailedEventRepository`; existing `ApplicationEventConsumer.onApproved(String)`.
- Produces: `GET /api/v1/ops/failed-events?status=FAILED|RESOLVED|ALL&page&size` → Spring `Page` JSON (`content`, `totalPages`, `totalElements`, `number`, `size`) of `FailedEventResponse{id, topic, messageKey, errorMessage, status, attempts, createdAt, updatedAt}`; `POST /api/v1/ops/failed-events/{id}/retry` → `FailedEventResponse`.

- [ ] **Step 1: Write the failing tests** — add to `FailedEventsIntegrationTest` (imports: `org.springframework.security.core.authority.SimpleGrantedAuthority`, static `SecurityMockMvcRequestPostProcessors.jwt`, static `MockMvcRequestBuilders.get`/`post`, static `MockMvcResultMatchers.jsonPath`/`status`, `org.springframework.test.web.servlet.request.RequestPostProcessor`):

```java
    static RequestPostProcessor ops() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS"));
    }

    @Test
    void list_returnsFailedEventsWithoutThePayload_forOps() throws Exception {
        UUID applicationId = UuidCreator.getTimeOrderedEpoch();
        failedEventFor(applicationId, approvedEnvelope(applicationId));

        mockMvc.perform(get("/api/v1/ops/failed-events").param("size", "100").with(ops()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPages").exists())
                .andExpect(jsonPath("$.content[?(@.messageKey == '" + applicationId + "')].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].payload").doesNotExist());
    }

    @Test
    void list_rejectsAnUnknownStatus() throws Exception {
        mockMvc.perform(get("/api/v1/ops/failed-events").param("status", "BOGUS").with(ops()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void endpoints_forbidNonOps() throws Exception {
        mockMvc.perform(get("/api/v1/ops/failed-events").with(jwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/ops/failed-events/" + UUID.randomUUID() + "/retry").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void retry_resolvesTheRowAndCreatesThePlan_oncePaddleRecovers() throws Exception {
        UUID applicationId = UuidCreator.getTimeOrderedEpoch();
        FailedEvent row = failedEventFor(applicationId, approvedEnvelope(applicationId));
        PADDLE.failing.set(false);

        mockMvc.perform(post("/api/v1/ops/failed-events/" + row.getId() + "/retry").with(ops()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        assertThat(repaymentPlanRepository.findByApplicationId(applicationId)).isPresent();

        mockMvc.perform(post("/api/v1/ops/failed-events/" + row.getId() + "/retry").with(ops()))
                .andExpect(status().isConflict());
    }

    @Test
    void retry_thatFailsAgain_keepsTheRowFailedAndCountsTheAttempt() throws Exception {
        UUID applicationId = UuidCreator.getTimeOrderedEpoch();
        FailedEvent row = failedEventFor(applicationId, approvedEnvelope(applicationId));
        // PADDLE still failing

        mockMvc.perform(post("/api/v1/ops/failed-events/" + row.getId() + "/retry").with(ops()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.attempts").value(2))
                .andExpect(jsonPath("$.errorMessage").value("paddle down"));
    }

    @Test
    void retry_ofAnOtherTopic_isAConflict() throws Exception {
        FailedEvent other = failedEventRepository.save(new FailedEvent("some.other-topic", "k", "{}", "boom"));

        mockMvc.perform(post("/api/v1/ops/failed-events/" + other.getId() + "/retry").with(ops()))
                .andExpect(status().isConflict());
    }

    @Test
    void retry_ofAnUnknownId_isNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/ops/failed-events/" + UUID.randomUUID() + "/retry").with(ops()))
                .andExpect(status().isNotFound());
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd services/repayment-reconciliation-service && mvn test -Dtest=FailedEventsIntegrationTest`
Expected: the 7 new tests FAIL (404 on unmapped paths / wrong status); Task 1's test still passes.

- [ ] **Step 3: DTO** — `FailedEventResponse.java`:

```java
package com.bridgepay.repayment.dto;

import com.bridgepay.repayment.domain.FailedEvent;
import com.bridgepay.repayment.domain.FailedEventStatus;

import java.time.Instant;
import java.util.UUID;

/** Deliberately omits payload - it can be large and the ops page doesn't show it. */
public record FailedEventResponse(
        UUID id,
        String topic,
        String messageKey,
        String errorMessage,
        FailedEventStatus status,
        int attempts,
        Instant createdAt,
        Instant updatedAt
) {
    public static FailedEventResponse from(FailedEvent event) {
        return new FailedEventResponse(event.getId(), event.getTopic(), event.getMessageKey(),
                event.getErrorMessage(), event.getStatus(), event.getAttempts(),
                event.getCreatedAt(), event.getUpdatedAt());
    }
}
```

- [ ] **Step 4: Service** — `FailedEventService.java`:

```java
package com.bridgepay.repayment.service;

import com.bridgepay.repayment.consumer.ApplicationEventConsumer;
import com.bridgepay.repayment.domain.FailedEvent;
import com.bridgepay.repayment.domain.FailedEventStatus;
import com.bridgepay.repayment.dto.FailedEventResponse;
import com.bridgepay.repayment.repository.FailedEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Lists and retries failed_events rows. Retry replays the stored payload
 * in-process through the same listener method Kafka would call - safe
 * because createPlanFromApprovedApplication is idempotent per application.
 * Deliberately not @Transactional: the replay runs its own transaction, and
 * wrapping it would mark this one rollback-only when the replay throws.
 */
@Service
public class FailedEventService {

    static final String APPROVED_TOPIC = "applications.approved";

    private final FailedEventRepository failedEventRepository;
    private final ApplicationEventConsumer applicationEventConsumer;

    public FailedEventService(FailedEventRepository failedEventRepository,
                              ApplicationEventConsumer applicationEventConsumer) {
        this.failedEventRepository = failedEventRepository;
        this.applicationEventConsumer = applicationEventConsumer;
    }

    public Page<FailedEventResponse> list(String status, Pageable pageable) {
        Page<FailedEvent> page = "ALL".equals(status)
                ? failedEventRepository.findAllByOrderByCreatedAtDesc(pageable)
                : failedEventRepository.findByStatusOrderByCreatedAtDesc(FailedEventStatus.valueOf(status), pageable);
        return page.map(FailedEventResponse::from);
    }

    public FailedEventResponse retry(UUID id) {
        FailedEvent event = failedEventRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No failed event " + id));
        if (event.getStatus() == FailedEventStatus.RESOLVED) {
            throw new IllegalStateException("Failed event " + id + " is already resolved");
        }
        if (!APPROVED_TOPIC.equals(event.getTopic())) {
            throw new IllegalStateException("No retry handler for topic " + event.getTopic());
        }
        try {
            applicationEventConsumer.onApproved(event.getPayload());
            event.markResolved();
        } catch (Exception ex) {
            event.recordFailedRetry(FailedEvent.describe(ex));
        }
        return FailedEventResponse.from(failedEventRepository.save(event));
    }
}
```

- [ ] **Step 5: Controller** — `FailedEventController.java`:

```java
package com.bridgepay.repayment.web;

import com.bridgepay.repayment.dto.FailedEventResponse;
import com.bridgepay.repayment.service.FailedEventService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ops/failed-events")
public class FailedEventController {

    private final FailedEventService failedEventService;

    public FailedEventController(FailedEventService failedEventService) {
        this.failedEventService = failedEventService;
    }

    @GetMapping
    @PreAuthorize("hasRole('OPS')")
    public Page<FailedEventResponse> list(@RequestParam(defaultValue = "FAILED") String status, Pageable pageable) {
        return failedEventService.list(status, pageable);
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasRole('OPS')")
    public FailedEventResponse retry(@PathVariable UUID id) {
        return failedEventService.retry(id);
    }
}
```

- [ ] **Step 6: Exception handlers** — add to `GlobalExceptionHandler` (imports `org.springframework.dao.OptimisticLockingFailureException`):

```java
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error("CONFLICT", "This record was changed concurrently - reload and try again"));
    }
```

- [ ] **Step 7: Run to verify they pass**

Run: `cd services/repayment-reconciliation-service && mvn test -Dtest=FailedEventsIntegrationTest`
Expected: PASS, 8 tests.

- [ ] **Step 8: Commit**

```bash
git add services/repayment-reconciliation-service
git commit -m "repayment-reconciliation: ops API to list and retry failed events"
```

---

### Task 3: `local`-profile security parity

**Files:**
- Modify: `services/repayment-reconciliation-service/src/main/java/com/bridgepay/repayment/config/LocalDevSecurityConfig.java` (replace whole file)
- Test: `services/repayment-reconciliation-service/src/test/java/com/bridgepay/repayment/LocalProfileSecurityIntegrationTest.java` (extend)

**Interfaces:**
- Consumes: Task 2's `GET /api/v1/ops/failed-events`.

- [ ] **Step 1: Write the failing tests** — add to `LocalProfileSecurityIntegrationTest`:

```java
    @Test
    void opsEndpoints_rejectRequestsWithNoToken() throws Exception {
        mockMvc.perform(get("/api/v1/ops/failed-events"))
                .andExpect(status().isForbidden());
    }

    @Test
    void opsEndpoints_rejectARealBearerTokenWithoutTheOpsRole() throws Exception {
        mockMvc.perform(get("/api/v1/ops/failed-events")
                        .header("Authorization", "Bearer " + tokenWithRoles(UUID.randomUUID().toString(), "shopper")))
                .andExpect(status().isForbidden());
    }

    @Test
    void opsEndpoints_allowARealBearerTokenCarryingTheOpsRole() throws Exception {
        mockMvc.perform(get("/api/v1/ops/failed-events")
                        .header("Authorization", "Bearer " + tokenWithRoles(UUID.randomUUID().toString(), "ops")))
                .andExpect(status().isOk());
    }

    private static String tokenWithRoles(String subject, String... roles) {
        String roleList = String.join(",", java.util.Arrays.stream(roles).map(r -> "\"" + r + "\"").toList());
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url("{\"sub\":\"" + subject + "\",\"realm_access\":{\"roles\":[" + roleList + "]}}");
        return header + "." + payload + ".";
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd services/repayment-reconciliation-service && mvn test -Dtest=LocalProfileSecurityIntegrationTest`
Expected: `opsEndpoints_rejectRequestsWithNoToken` and `..._WithoutTheOpsRole` FAIL (200 — method security off under `local`); the ops-role test passes; the two existing tests pass.

- [ ] **Step 3: Implement** — replace `LocalDevSecurityConfig.java` with Application Service's version (`services/application-service/src/main/java/com/bridgepay/application/config/LocalDevSecurityConfig.java`) verbatim, changing only the package to `com.bridgepay.repayment.config` and the Javadoc's last sentence to "Copied verbatim from application-service's LocalDevSecurityConfig." It carries `@EnableMethodSecurity`, copies every real claim (minus `iat`/`exp`/`nbf`), and maps `realm_access.roles` to `ROLE_<UPPER>` authorities.

- [ ] **Step 4: Run the whole service suite**

Run: `cd services/repayment-reconciliation-service && mvn clean verify`
Expected: BUILD SUCCESS; baseline + 8 (Task 1–2) + 3 (Task 3) tests; existing `repayment-plans` owner tests still pass.

- [ ] **Step 5: Commit**

```bash
git add services/repayment-reconciliation-service
git commit -m "repayment-reconciliation: enforce method security and real roles under local profile"
```

---

### Task 4: Gateway route

**Files:**
- Modify: `services/api-gateway/src/main/resources/application.yaml` (routes list)
- Test: `services/api-gateway/src/test/java/com/bridgepay/gateway/RoutingIntegrationTest.java`

- [ ] **Step 1: Write the failing test** — add after `routesRepaymentPlanPaths_toRepaymentReconciliationService`:

```java
    @Test
    void routesOpsPaths_toRepaymentReconciliationService() throws Exception {
        wireMock.stubFor(WireMock.post(urlPathEqualTo("/api/v1/ops/failed-events/e-1/retry"))
                .willReturn(okJson("{\"status\":\"RESOLVED\"}")));

        mockMvc.perform(post("/api/v1/ops/failed-events/e-1/retry"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"RESOLVED\"}"));
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd services/api-gateway && mvn test -Dtest=RoutingIntegrationTest`
Expected: the new test FAILS with 404 (no route).

- [ ] **Step 3: Implement** — append to the routes list in `application.yaml`:

```yaml
            - id: ops
              uri: ${bridgepay.repayment-reconciliation-service.base-url}
              predicates:
                - Path=/api/v1/ops/**
```

- [ ] **Step 4: Run the gateway suite**

Run: `cd services/api-gateway && mvn clean verify`
Expected: BUILD SUCCESS, all green.

- [ ] **Step 5: Commit**

```bash
git add services/api-gateway
git commit -m "api-gateway: route /api/v1/ops/** to repayment-reconciliation-service"
```

---

### Task 5: `main-app` Failed events page

**Files:**
- Create: `frontend/main-app/src/app/shared/models/failed-event.ts`
- Create: `frontend/main-app/src/app/ops/failed-events-api.ts`, `failed-events-api.spec.ts`
- Create: `frontend/main-app/src/app/ops/failed-events/failed-events.ts`, `.html`, `.css` (empty), `.spec.ts`
- Modify: `frontend/main-app/src/app/app.routes.ts`, `frontend/main-app/src/app/app.html`
- Test: `frontend/main-app/src/app/app.routes.spec.ts` (extend)

**Interfaces:**
- Consumes: Task 2/4 API through the gateway at `/api/v1/ops/failed-events`.
- Produces: `FailedEventsApi.list(status: string, page: number, size = 20): Observable<Page<FailedEvent>>`, `FailedEventsApi.retry(id: string): Observable<FailedEvent>`; component `FailedEventsPage`.

Ruling vs spec: after a retry the page **refetches** the current page instead of splicing the returned row in — a resolved row then correctly drops out of the default Failed filter. A retry that comes back still `FAILED` shows "Retry failed: <error>".

- [ ] **Step 1: Write the failing tests**

`failed-events-api.spec.ts`:

```ts
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FailedEventsApi } from './failed-events-api';

describe('FailedEventsApi', () => {
  let api: FailedEventsApi;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(FailedEventsApi);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists with status, page and size', () => {
    api.list('FAILED', 2).subscribe();
    const req = httpMock.expectOne(
      (r) => r.url.endsWith('/api/v1/ops/failed-events') && r.params.get('status') === 'FAILED'
        && r.params.get('page') === '2' && r.params.get('size') === '20',
    );
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 2, size: 20 });
  });

  it('retries by POSTing to the row', () => {
    api.retry('e-1').subscribe();
    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/ops/failed-events/e-1/retry'));
    expect(req.request.method).toBe('POST');
    req.flush({});
  });
});
```

`failed-events/failed-events.spec.ts`:

```ts
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { FailedEventsPage } from './failed-events';
import { FailedEventsApi } from '../failed-events-api';
import { FailedEvent } from '../../shared/models/failed-event';
import { Page } from '../../shared/models/page';

const row: FailedEvent = {
  id: 'e-1', topic: 'applications.approved', messageKey: 'app-123', errorMessage: 'paddle down',
  status: 'FAILED', attempts: 1, createdAt: '2026-09-25T10:00:00Z', updatedAt: '2026-09-25T10:00:00Z',
};

function page(content: FailedEvent[]): Page<FailedEvent> {
  return { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 };
}

function setup(stub: {
  list?: (status: string, page: number) => Observable<Page<FailedEvent>>;
  retry?: (id: string) => Observable<FailedEvent>;
}) {
  TestBed.configureTestingModule({
    imports: [FailedEventsPage],
    providers: [{
      provide: FailedEventsApi,
      useValue: { list: stub.list ?? (() => of(page([row]))), retry: stub.retry ?? (() => of({ ...row, status: 'RESOLVED' })) },
    }],
  });
  const fixture = TestBed.createComponent(FailedEventsPage);
  fixture.detectChanges();
  return fixture;
}

function button(el: HTMLElement, label: string): HTMLButtonElement {
  return Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === label) as HTMLButtonElement;
}

describe('FailedEventsPage', () => {
  it('loads failed events on page 0 by default and renders a row', () => {
    const calls: [string, number][] = [];
    const el = setup({ list: (s, p) => (calls.push([s, p]), of(page([row]))) }).nativeElement as HTMLElement;

    expect(calls).toEqual([['FAILED', 0]]);
    const text = el.textContent ?? '';
    expect(text).toContain('app-123');
    expect(text).toContain('paddle down');
    expect(text).toContain('applications.approved');
  });

  it('refetches when a different filter is clicked', () => {
    const calls: string[] = [];
    const fixture = setup({ list: (s) => (calls.push(s), of(page([]))) });

    button(fixture.nativeElement, 'Resolved').click();
    fixture.detectChanges();

    expect(calls).toEqual(['FAILED', 'RESOLVED']);
  });

  it('shows a load error instead of the empty state', () => {
    const el = setup({ list: () => throwError(() => new Error('403')) }).nativeElement as HTMLElement;

    expect(el.textContent).toContain('Could not load failed events');
    expect(el.textContent).not.toContain('No failed events');
  });

  it('shows the empty state when there is nothing to show', () => {
    const el = setup({ list: () => of(page([])) }).nativeElement as HTMLElement;

    expect(el.textContent).toContain('No failed events');
  });

  it('retries a row and refetches the list', () => {
    const listCalls: string[] = [];
    const retried: string[] = [];
    const fixture = setup({
      list: (s) => (listCalls.push(s), of(page([row]))),
      retry: (id) => (retried.push(id), of({ ...row, status: 'RESOLVED' })),
    });

    button(fixture.nativeElement, 'Retry').click();
    fixture.detectChanges();

    expect(retried).toEqual(['e-1']);
    expect(listCalls).toEqual(['FAILED', 'FAILED']);
  });

  it('disables Retry while a retry is in flight', () => {
    const pending = new Subject<FailedEvent>();
    const fixture = setup({ retry: () => pending });

    button(fixture.nativeElement, 'Retry').click();
    fixture.detectChanges();

    expect(button(fixture.nativeElement, 'Retry').disabled).toBe(true);
  });

  it('says so when a retry comes back still failing', () => {
    const fixture = setup({ retry: () => of({ ...row, attempts: 2, errorMessage: 'still down' }) });

    button(fixture.nativeElement, 'Retry').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Retry failed: still down');
  });

  it('shows an error when the retry request itself fails', () => {
    const fixture = setup({ retry: () => throwError(() => new Error('500')) });

    button(fixture.nativeElement, 'Retry').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Could not retry this event');
  });
});
```

Add to `app.routes.spec.ts` (read the file first and follow its existing structure; the assertion is):

```ts
  it('routes /ops/failed-events to the failed events page, not review detail', () => {
    const opsIndex = routes.findIndex((r) => r.path === 'ops/failed-events');
    const detailIndex = routes.findIndex((r) => r.path === 'ops/:id');
    expect(opsIndex).toBeGreaterThan(-1);
    expect(routes[opsIndex].component).toBe(FailedEventsPage);
    expect(opsIndex).toBeLessThan(detailIndex);
  });
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend/main-app && npx ng test --watch=false`
Expected: compilation/test FAIL — `FailedEventsApi`, `FailedEventsPage`, `failed-event` model missing.

- [ ] **Step 3: Model** — `shared/models/failed-event.ts`:

```ts
export interface FailedEvent {
  id: string;
  topic: string;
  messageKey: string | null;
  errorMessage: string;
  status: 'FAILED' | 'RESOLVED';
  attempts: number;
  createdAt: string;
  updatedAt: string;
}
```

- [ ] **Step 4: API service** — `ops/failed-events-api.ts`:

```ts
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { Page } from '../shared/models/page';
import { FailedEvent } from '../shared/models/failed-event';

@Injectable({ providedIn: 'root' })
export class FailedEventsApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.gatewayBaseUrl}/api/v1/ops/failed-events`;

  list(status: string, page: number, size = 20): Observable<Page<FailedEvent>> {
    return this.http.get<Page<FailedEvent>>(this.baseUrl, { params: { status, page, size } });
  }

  retry(id: string): Observable<FailedEvent> {
    return this.http.post<FailedEvent>(`${this.baseUrl}/${id}/retry`, {});
  }
}
```

- [ ] **Step 5: Component** — `ops/failed-events/failed-events.ts`:

```ts
import { Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { catchError, of, switchMap } from 'rxjs';
import { FailedEventsApi } from '../failed-events-api';
import { FailedEvent } from '../../shared/models/failed-event';

export const FAILED_EVENT_FILTERS = ['FAILED', 'RESOLVED', 'ALL'] as const;
export type FailedEventFilter = (typeof FAILED_EVENT_FILTERS)[number];

const FILTER_LABELS: Record<FailedEventFilter, string> = { FAILED: 'Failed', RESOLVED: 'Resolved', ALL: 'All' };

@Component({
  selector: 'app-failed-events',
  imports: [DatePipe],
  templateUrl: './failed-events.html',
  styleUrl: './failed-events.css',
})
export class FailedEventsPage {
  private readonly api = inject(FailedEventsApi);

  protected readonly filters = FAILED_EVENT_FILTERS;
  protected readonly filterLabels = FILTER_LABELS;
  protected readonly filter = signal<FailedEventFilter>('FAILED');
  protected readonly page = signal(0);
  protected readonly listError = signal(false);
  protected readonly retrying = signal<string | null>(null);
  protected readonly retryMessage = signal<string | null>(null);
  // Bumped after a retry so the current page refetches with the row's new status.
  private readonly reload = signal(0);

  private readonly query = computed(() => ({ status: this.filter(), page: this.page(), reload: this.reload() }));

  private readonly result = toSignal(
    toObservable(this.query).pipe(
      switchMap(({ status, page }) => {
        this.listError.set(false);
        return this.api.list(status, page).pipe(
          catchError(() => {
            this.listError.set(true);
            return of(null);
          }),
        );
      }),
    ),
    { initialValue: null },
  );

  protected readonly rows = computed(() => this.result()?.content ?? []);
  protected readonly totalPages = computed(() => this.result()?.totalPages ?? 0);

  protected selectFilter(status: FailedEventFilter): void {
    this.filter.set(status);
    this.page.set(0);
  }

  protected prevPage(): void {
    this.page.update((p) => Math.max(0, p - 1));
  }

  protected nextPage(): void {
    this.page.update((p) => p + 1);
  }

  protected retry(row: FailedEvent): void {
    if (this.retrying()) return;
    this.retrying.set(row.id);
    this.retryMessage.set(null);
    this.api.retry(row.id).subscribe({
      next: (updated) => {
        this.retrying.set(null);
        if (updated.status === 'FAILED') this.retryMessage.set(`Retry failed: ${updated.errorMessage}`);
        this.reload.update((n) => n + 1);
      },
      error: () => {
        this.retrying.set(null);
        this.retryMessage.set('Could not retry this event. Try again.');
      },
    });
  }
}
```

`ops/failed-events/failed-events.html`:

```html
<h1 class="text-xl font-medium mb-4">Failed events</h1>
<div class="flex gap-2 mb-4">
  @for (status of filters; track status) {
    <button
      type="button"
      (click)="selectFilter(status)"
      class="px-3 py-1 text-sm border rounded"
      [class.border-accent]="filter() === status"
      [class.text-accent]="filter() === status"
      [class.border-hairline]="filter() !== status"
      [class.text-ink-muted]="filter() !== status"
    >{{ filterLabels[status] }}</button>
  }
</div>
@if (retryMessage()) {
  <p class="mb-4 text-sm text-status-declined">{{ retryMessage() }}</p>
}
<table class="w-full text-sm">
  <thead>
    <tr class="border-b border-hairline text-left text-ink-muted">
      <th class="py-2 font-normal">Failed at</th>
      <th class="py-2 font-normal">Topic</th>
      <th class="py-2 font-normal">Application</th>
      <th class="py-2 font-normal">Error</th>
      <th class="py-2 font-normal text-right">Attempts</th>
      <th class="py-2 font-normal">Status</th>
      <th class="py-2"></th>
    </tr>
  </thead>
  <tbody>
    @if (listError()) {
      <tr>
        <td colspan="7" class="py-6 text-center text-status-declined">Could not load failed events. Try refreshing.</td>
      </tr>
    } @else {
      @for (row of rows(); track row.id) {
        <tr class="border-b border-hairline align-top">
          <td class="py-2 font-mono">{{ row.createdAt | date: 'MMM d, y HH:mm' }}</td>
          <td class="py-2 font-mono">{{ row.topic }}</td>
          <td class="py-2 font-mono">{{ row.messageKey ?? '—' }}</td>
          <td class="py-2 max-w-md break-words">{{ row.errorMessage }}</td>
          <td class="py-2 font-mono text-right">{{ row.attempts }}</td>
          <td class="py-2 font-mono">{{ row.status }}</td>
          <td class="py-2 text-right">
            @if (row.status === 'FAILED') {
              <button
                type="button"
                (click)="retry(row)"
                [disabled]="retrying() !== null"
                class="text-accent hover:underline disabled:opacity-50"
              >Retry</button>
            }
          </td>
        </tr>
      } @empty {
        <tr>
          <td colspan="7" class="py-6 text-center text-ink-muted">No failed events.</td>
        </tr>
      }
    }
  </tbody>
</table>
<div class="flex items-center gap-3 mt-4 text-sm">
  <button type="button" (click)="prevPage()" [disabled]="page() === 0" class="px-3 py-1 border border-hairline rounded disabled:opacity-40">Prev</button>
  <span class="text-ink-muted font-mono">Page {{ page() + 1 }} of {{ totalPages() || 1 }}</span>
  <button type="button" (click)="nextPage()" [disabled]="page() + 1 >= totalPages()" class="px-3 py-1 border border-hairline rounded disabled:opacity-40">Next</button>
</div>
```

`ops/failed-events/failed-events.css`: empty file.

- [ ] **Step 6: Route + sidebar**

In `app.routes.ts` import `FailedEventsPage` from `./ops/failed-events/failed-events` and insert **between** the `ops` and `ops/:id` entries:

```ts
  { path: 'ops/failed-events', component: FailedEventsPage, canActivate: [opsGuard] },
```

In `app.html`, inside the `@if (auth.hasRole('ops'))` block after the Review Queue link:

```html
      <a
        routerLink="/ops/failed-events"
        routerLinkActive="text-accent font-medium"
        class="px-2 py-1.5 rounded-sm hover:bg-hairline/40"
      >Failed Events</a>
```

- [ ] **Step 7: Run to verify they pass**

Run: `cd frontend/main-app && npx ng test --watch=false && npx ng build`
Expected: all tests green (42 baseline + 11 new); build succeeds.

- [ ] **Step 8: Commit**

```bash
git add frontend/main-app
git commit -m "main-app: ops Failed Events page with filter, pagination and retry"
```

---

### Task 6: Manual verification + PROGRESS.md

- [ ] **Step 1:** `docker compose ps` from the repo root — don't stop anything already running. Use an isolated project name (`docker compose -p failedevents ...`) if bringing up from a worktree.
- [ ] **Step 2:** `docker compose -p failedevents up --build -d` (full stack; placeholder `PADDLE_API_KEY=changeme` makes every Paddle call `403`).
- [ ] **Step 3:** Get real Keycloak tokens for `shopper1` and `ops1` (password grant against `main-app` client, same as the merchant-sales-view verification). As `shopper1`, sign up if needed and check out through the gateway (`POST /api/v1/applications/checkout`); as `ops1`, approve it via `POST /api/v1/applications/{id}/review-decision` if it lands in `MANUAL_REVIEW`.
- [ ] **Step 4:** Within ~10s, `GET http://localhost:8086/api/v1/ops/failed-events` as `ops1` → a `FAILED` row whose `messageKey` is the application id and whose `errorMessage` mentions Paddle's 403. As `shopper1` → `403`.
- [ ] **Step 5:** `POST .../{id}/retry` as `ops1` → `200`, `status: FAILED`, `attempts: 2`.
- [ ] **Step 6:** `curl -s -o /dev/null -w "%{http_code}" http://localhost:4200/ops/failed-events` → `200` (SPA shell).
- [ ] **Step 7:** `docker compose -p failedevents down -v`.
- [ ] **Step 8:** Add a `[x] Failed events recovery` Done entry to `docs/PROGRESS.md` (what shipped, test counts, the live results from Steps 4–6, not-verified: browser click-through, a real `RESOLVED` without a real Paddle key); update "Next, in order" so k3s manifests are next. Commit:

```bash
git add docs/PROGRESS.md
git commit -m "Record the failed events recovery feature in PROGRESS.md"
```
