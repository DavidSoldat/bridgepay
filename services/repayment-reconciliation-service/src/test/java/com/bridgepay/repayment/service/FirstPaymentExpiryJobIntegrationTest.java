package com.bridgepay.repayment.service;

import com.bridgepay.repayment.client.PaddleClient;
import com.bridgepay.repayment.client.PaddleTransactionResult;
import com.bridgepay.repayment.client.PaddleUnavailableException;
import com.bridgepay.repayment.domain.Installment;
import com.bridgepay.repayment.domain.PlanStatus;
import com.bridgepay.repayment.domain.RepaymentPlan;
import com.bridgepay.repayment.repository.InstallmentRepository;
import com.bridgepay.repayment.repository.OutboxEventRepository;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Postgres + Kafka via Testcontainers, matching this service's other
 * @SpringBootTest classes (its Kafka consumer needs a broker to start).
 */
@SpringBootTest
@Import(FirstPaymentExpiryJobIntegrationTest.TestConfig.class)
class FirstPaymentExpiryJobIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bridgepay").withUsername("test").withPassword("test");
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:latest"));
    static final RecordingPaddleClient PADDLE = new RecordingPaddleClient();

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
            return token -> Jwt.withTokenValue(token).header("alg", "none").claim("sub", "unused")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        }

        @Bean
        @Primary
        PaddleClient paddleClient() {
            return PADDLE;
        }
    }

    static class RecordingPaddleClient implements PaddleClient {
        final List<String> cancelled = new CopyOnWriteArrayList<>();
        final Set<String> refuse = ConcurrentHashMap.newKeySet();

        @Override
        public String findOrCreateCustomer(String email, String name) {
            return "ctm_unused";
        }

        @Override
        public PaddleTransactionResult createInstallmentTransaction(String customerId, BigDecimal installmentAmount) {
            throw new UnsupportedOperationException("not used by these tests");
        }

        @Override
        public void cancelSubscription(String subscriptionId) {
        }

        @Override
        public void cancelTransaction(String transactionId) {
            if (refuse.contains(transactionId)) {
                throw new PaddleUnavailableException("transaction already completed", null);
            }
            cancelled.add(transactionId);
        }
    }

    @Autowired
    FirstPaymentExpiryJob job;
    @Autowired
    RepaymentPlanRepository repaymentPlanRepository;
    @Autowired
    InstallmentRepository installmentRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;

    private RepaymentPlan plan(String transactionId) {
        RepaymentPlan plan = repaymentPlanRepository.save(new RepaymentPlan(UUID.randomUUID(), UUID.randomUUID(),
                "ctm_x", transactionId, new BigDecimal("200.00"), 4, new BigDecimal("50.00")));
        for (int sequence = 1; sequence <= 4; sequence++) {
            installmentRepository.save(new Installment(plan, sequence,
                    LocalDate.now().plusWeeks(sequence - 1L), new BigDecimal("50.00")));
        }
        return plan;
    }

    private PlanStatus statusOf(RepaymentPlan plan) {
        return repaymentPlanRepository.findById(plan.getId()).orElseThrow().getStatus();
    }

    @Test
    void expiresAnUnpaidPlanPastTheCutoff_inPaddleAndWithAnEvent() {
        RepaymentPlan plan = plan("txn_expire_1");

        job.expireCreatedBefore(Instant.now().plusSeconds(1));

        assertThat(statusOf(plan)).isEqualTo(PlanStatus.CANCELLED);
        assertThat(PADDLE.cancelled).contains("txn_expire_1");
        assertThat(outboxEventRepository.findAll()).anySatisfy(event -> {
            assertThat(event.getTopic()).isEqualTo("repayments.plan-cancelled");
            assertThat(event.getPartitionKey()).isEqualTo(plan.getId().toString());
            assertThat(event.getPayload()).contains(plan.getApplicationId().toString());
        });
    }

    @Test
    void leavesAPlanCreatedAfterTheCutoffAlone() {
        RepaymentPlan plan = plan("txn_young_1");

        job.expireCreatedBefore(Instant.now().minusSeconds(60));

        assertThat(statusOf(plan)).isEqualTo(PlanStatus.ACTIVE);
        assertThat(PADDLE.cancelled).doesNotContain("txn_young_1");
    }

    @Test
    void leavesAPlanWhoseFirstInstallmentIsPaidAlone() {
        RepaymentPlan plan = plan("txn_paid_1");
        Installment first = installmentRepository.findByRepaymentPlanOrderBySequenceNumberAsc(plan).get(0);
        first.markPaid("txn_paid_1");
        installmentRepository.save(first);

        job.expireCreatedBefore(Instant.now().plusSeconds(1));

        assertThat(statusOf(plan)).isEqualTo(PlanStatus.ACTIVE);
        assertThat(PADDLE.cancelled).doesNotContain("txn_paid_1");
    }

    @Test
    void paddleRefusal_leavesThatPlanActive_andStillExpiresTheNext() {
        RepaymentPlan refused = plan("txn_refuse_1");
        RepaymentPlan next = plan("txn_after_refuse_1");
        PADDLE.refuse.add("txn_refuse_1");

        job.expireCreatedBefore(Instant.now().plusSeconds(1));

        assertThat(statusOf(refused)).isEqualTo(PlanStatus.ACTIVE);
        assertThat(statusOf(next)).isEqualTo(PlanStatus.CANCELLED);
    }
}
