package com.bridgepay.repayment.web;

import com.bridgepay.repayment.client.PaddleClient;
import com.bridgepay.repayment.client.PaddleWebhookData;
import com.bridgepay.repayment.domain.Installment;
import com.bridgepay.repayment.domain.RepaymentPlan;
import com.bridgepay.repayment.repository.InstallmentRepository;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import com.bridgepay.repayment.service.PaddleWebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
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
    // never the real sandbox from a test; unstubbed = "not completed yet"
    @MockitoBean
    private PaddleClient paddleClient;
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

    @Autowired
    private PaddleWebhookService paddleWebhookService;

    private RepaymentPlan planWithFourInstallments(UUID applicationId, String subject, String transactionId) {
        RepaymentPlan plan = repaymentPlanRepository.save(new RepaymentPlan(applicationId, UUID.fromString(subject),
                "ctm_x", transactionId, new BigDecimal("200.00"), 4, new BigDecimal("50.00")));
        for (int sequence = 1; sequence <= 4; sequence++) {
            installmentRepository.save(new Installment(plan, sequence,
                    LocalDate.of(2026, 1, 1).plusWeeks(sequence - 1L), new BigDecimal("50.00")));
        }
        return plan;
    }

    @Test
    void checkoutTransactionId_isTheInitialTransaction_whileTheFirstInstallmentIsUnpaid() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        planWithFourInstallments(applicationId, subject, "txn_unpaid");

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId).with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutTransactionId").value("txn_unpaid"));
    }

    @Test
    void checkoutTransactionId_survivesADeclinedFirstAttempt() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        planWithFourInstallments(applicationId, subject, "txn_declined");

        paddleWebhookService.handle("transaction.payment_failed", new PaddleWebhookData("txn_declined", null));

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId).with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(jsonPath("$.installments[0].status").value("LATE"))
                .andExpect(jsonPath("$.checkoutTransactionId").value("txn_declined"));
    }

    @Test
    void firstPayment_adoptsTheSubscription_soALaterCancelDefaultsThePlan() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        planWithFourInstallments(applicationId, subject, "txn_first");

        paddleWebhookService.handle("transaction.completed", new PaddleWebhookData("txn_first", "sub_first"));

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId).with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(jsonPath("$.installments[0].status").value("PAID"))
                .andExpect(jsonPath("$.checkoutTransactionId").doesNotExist());

        paddleWebhookService.handle("subscription.canceled", new PaddleWebhookData("sub_first", null));

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId).with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(jsonPath("$.status").value("DEFAULTED"))
                .andExpect(jsonPath("$.checkoutTransactionId").doesNotExist());
    }

    @Test
    void get_returnsNotFound_whenNoPlanExistsForThisApplication() throws Exception {
        mockMvc.perform(get("/api/v1/repayment-plans/" + UUID.randomUUID())
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_letsOpsReadAnyShoppersPlan() throws Exception {
        UUID applicationId = UUID.randomUUID();
        repaymentPlanRepository.save(new RepaymentPlan(applicationId, UUID.randomUUID(),
                "ctm_ops", "txn_ops", new BigDecimal("100.00"), 4, new BigDecimal("25.00")));

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId)
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_OPS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(applicationId.toString()));
    }

    @Test
    void read_appliesAFirstPaymentPaddleCompleted_andALateWebhookThenChangesNothing() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        planWithFourInstallments(applicationId, subject, "txn_lost_webhook");
        PaddleWebhookData completed = new PaddleWebhookData("txn_lost_webhook", "sub_lost_webhook");
        when(paddleClient.findCompletedTransaction("txn_lost_webhook")).thenReturn(Optional.of(completed));

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId).with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installments[0].status").value("PAID"))
                .andExpect(jsonPath("$.checkoutTransactionId").doesNotExist());

        paddleWebhookService.handle("transaction.completed", completed);

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId).with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(jsonPath("$.installments[0].status").value("PAID"))
                .andExpect(jsonPath("$.installments[1].status").value("SCHEDULED"));
    }
}
