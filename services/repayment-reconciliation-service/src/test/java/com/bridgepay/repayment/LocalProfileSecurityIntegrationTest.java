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
import java.util.Arrays;
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
        String roleList = String.join(",", Arrays.stream(roles).map(r -> "\"" + r + "\"").toList());
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url("{\"sub\":\"" + subject + "\",\"realm_access\":{\"roles\":[" + roleList + "]}}");
        return header + "." + payload + ".";
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
