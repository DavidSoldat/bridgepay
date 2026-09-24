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
