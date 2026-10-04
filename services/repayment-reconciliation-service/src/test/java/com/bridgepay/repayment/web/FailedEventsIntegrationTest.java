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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        /** When set, the first transaction call parks here so a test can overlap a second request. */
        volatile CountDownLatch entered;
        volatile CountDownLatch release;

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
            CountDownLatch gate = release;
            if (gate != null && entered.getCount() > 0) {
                entered.countDown();
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new PaddleTransactionResult(id, "https://sandbox.paddle.com/checkout/" + id);
        }

        @Override
        public void cancelSubscription(String subscriptionId) {
        }

        @Override
        public void cancelTransaction(String transactionId) {
        }

        @Override
        public java.util.Optional<com.bridgepay.repayment.client.PaddleWebhookData> findCompletedTransaction(
                String transactionId) {
            return java.util.Optional.empty();
        }

        @Override
        public void chargeNow(String subscriptionId, java.math.BigDecimal installmentAmount, int quantity) {
            throw new UnsupportedOperationException("early payments are not used by this test");
        }

        @Override
        public java.util.Optional<com.bridgepay.repayment.client.PaddleWebhookData> findLatestChargeTransaction(
                String subscriptionId) {
            return java.util.Optional.empty();
        }

        @Override
        public String refundTransaction(String transactionId) {
            throw new UnsupportedOperationException("refunds are not used by this test");
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
        PADDLE.entered = null;
        PADDLE.release = null;
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
    void retry_whileAnotherRetryOfTheSameRowIsInFlight_isAConflictAndCallsPaddleOnce() throws Exception {
        UUID applicationId = UuidCreator.getTimeOrderedEpoch();
        FailedEvent row = failedEventFor(applicationId, approvedEnvelope(applicationId));
        PADDLE.failing.set(false);
        PADDLE.entered = new CountDownLatch(1);
        PADDLE.release = new CountDownLatch(1);
        int transactionsBefore = PADDLE.sequence.get();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> first = executor.submit(() -> mockMvc
                    .perform(post("/api/v1/ops/failed-events/" + row.getId() + "/retry").with(ops()))
                    .andReturn());
            assertThat(PADDLE.entered.await(10, TimeUnit.SECONDS)).isTrue();

            mockMvc.perform(post("/api/v1/ops/failed-events/" + row.getId() + "/retry").with(ops()))
                    .andExpect(status().isConflict());

            PADDLE.release.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        } finally {
            PADDLE.release.countDown();
            executor.shutdownNow();
        }

        assertThat(PADDLE.sequence.get() - transactionsBefore).isEqualTo(1);
        assertThat(failedEventRepository.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(FailedEventStatus.RESOLVED);
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

    @Test
    void retry_ofARefundRequest_replaysItThroughTheRefundHandler() throws Exception {
        // No plan exists for this application, so the replay fails again - proving it reached RefundService
        // rather than being rejected as an unknown topic (409).
        String payload = objectMapper.writeValueAsString(com.bridgepay.repayment.event.EventEnvelope.of(
                "application.refund-requested", UUID.randomUUID(),
                new com.bridgepay.repayment.event.ApplicationEvents.RefundRequested(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())));
        FailedEvent row = failedEventRepository.save(
                new FailedEvent("applications.refund-requested", "k", payload, "No repayment plan yet"));

        mockMvc.perform(post("/api/v1/ops/failed-events/" + row.getId() + "/retry").with(ops()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.attempts").value(2));
    }
}
