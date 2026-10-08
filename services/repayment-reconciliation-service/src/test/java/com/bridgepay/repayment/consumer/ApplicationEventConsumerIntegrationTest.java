package com.bridgepay.repayment.consumer;

import com.bridgepay.repayment.client.ApplicantClient;
import com.bridgepay.repayment.client.ApplicantProfile;
import com.bridgepay.repayment.client.PaddleClient;
import com.bridgepay.repayment.client.PaddleTransactionResult;
import com.bridgepay.repayment.domain.RepaymentPlan;
import com.bridgepay.repayment.event.ApplicationEvents;
import com.bridgepay.repayment.event.EventEnvelope;
import com.bridgepay.repayment.repository.InstallmentRepository;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import com.github.f4b6a3.uuid.UuidCreator;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Real Postgres + Kafka via Testcontainers (matching every other service);
 * ApplicantClient/PaddleClient are swapped for in-memory fakes since a real
 * Applicant Service and real Paddle sandbox can't run in this test.
 */
@SpringBootTest
@Import(ApplicationEventConsumerIntegrationTest.TestConfig.class)
class ApplicationEventConsumerIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bridgepay")
            .withUsername("test")
            .withPassword("test");

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

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
            return new FakeApplicantClient();
        }

        @Bean
        @Primary
        PaddleClient fakePaddleClient() {
            return new FakePaddleClient();
        }
    }

    static class FakeApplicantClient implements ApplicantClient {
        final ConcurrentHashMap<UUID, String> paddleCustomerIds = new ConcurrentHashMap<>();

        @Override
        public ApplicantProfile fetchProfile(UUID applicantId) {
            return new ApplicantProfile(applicantId, "Ana", "Doe", "ana-" + applicantId + "@example.com",
                    paddleCustomerIds.get(applicantId));
        }

        @Override
        public void setPaddleCustomerId(UUID applicantId, String paddleCustomerId) {
            paddleCustomerIds.put(applicantId, paddleCustomerId);
        }
    }

    static class FakePaddleClient implements PaddleClient {
        final AtomicInteger customerSequence = new AtomicInteger();
        final AtomicInteger transactionSequence = new AtomicInteger();

        @Override
        public String findOrCreateCustomer(String email, String name) {
            return "ctm_fake_" + customerSequence.incrementAndGet();
        }

        @Override
        public PaddleTransactionResult createInstallmentTransaction(String customerId, BigDecimal installmentAmount) {
            String id = "txn_fake_" + transactionSequence.incrementAndGet();
            return new PaddleTransactionResult(id, "https://sandbox.paddle.com/checkout/" + id);
        }

        @Override
        public void cancelSubscription(String subscriptionId) {
            // no-op
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
    private ObjectMapper objectMapper;
    @Autowired
    private RepaymentPlanRepository repaymentPlanRepository;
    @Autowired
    private InstallmentRepository installmentRepository;

    private void publish(String topic, String key, String value) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, key, value));
        }
    }

    private String approvedEnvelope(UUID applicationId, UUID applicantId) {
        var envelope = new EventEnvelope<>(UuidCreator.getTimeOrderedEpoch(), "application.approved", Instant.now(),
                applicationId, 1, new ApplicationEvents.Approved(applicantId, UuidCreator.getTimeOrderedEpoch(),
                        new BigDecimal("200.00"), 4, new BigDecimal("50.00")));
        return objectMapper.writeValueAsString(envelope);
    }

    @Test
    void approvedEvent_createsAPlanWithFourInstallments() {
        UUID applicationId = UuidCreator.getTimeOrderedEpoch();
        UUID applicantId = UuidCreator.getTimeOrderedEpoch();

        publish("applications.approved", applicationId.toString(), approvedEnvelope(applicationId, applicantId));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Optional<RepaymentPlan> plan = repaymentPlanRepository.findByApplicationId(applicationId);
            assertThat(plan).isPresent();
            assertThat(plan.get().getApplicantId()).isEqualTo(applicantId);
            assertThat(plan.get().getInstallmentCount()).isEqualTo(4);
            List<?> installments = installmentRepository.findByRepaymentPlanOrderBySequenceNumberAsc(plan.get());
            assertThat(installments).hasSize(4);
        });
    }

    @Test
    void redeliveredEvent_doesNotCreateASecondPlan() {
        UUID applicationId = UuidCreator.getTimeOrderedEpoch();
        UUID applicantId = UuidCreator.getTimeOrderedEpoch();
        String json = approvedEnvelope(applicationId, applicantId);

        publish("applications.approved", applicationId.toString(), json);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(repaymentPlanRepository.findByApplicationId(applicationId)).isPresent());

        publish("applications.approved", applicationId.toString(), json);

        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(repaymentPlanRepository.findAll().stream()
                        .filter(p -> p.getApplicationId().equals(applicationId)).count()).isEqualTo(1));
    }
}
