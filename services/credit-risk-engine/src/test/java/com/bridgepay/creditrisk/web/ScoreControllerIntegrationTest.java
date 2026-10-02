package com.bridgepay.creditrisk.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import com.bridgepay.creditrisk.client.BureauClient;
import com.bridgepay.creditrisk.client.BureauProfile;
import com.bridgepay.creditrisk.client.RepaymentHistory;
import com.bridgepay.creditrisk.client.RepaymentHistoryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full HTTP-path test against a real Redis (Testcontainers) and the real
 * ONNX Runtime, using the toy fixture from
 * src/test/resources/fixtures/generate_fixture.py in place of the real
 * model. BureauClient/RepaymentHistoryClient are stubbed so the test doesn't
 * depend on the Mock Credit Bureau or Repayment Reconciliation running.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ScoreControllerIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("bridgepay.credit-risk-model.model-path", () -> "classpath:fixtures/model.onnx");
        registry.add("bridgepay.credit-risk-model.coefficients-path", () -> "classpath:fixtures/coefficients.json");
    }

    @TestConfiguration
    static class TestOverrides {

        static final AtomicInteger bureauCallCount = new AtomicInteger();

        @Bean
        @Primary
        BureauClient stubBureauClient() {
            return applicantId -> {
                bureauCallCount.incrementAndGet();
                return new BureauProfile(0.5, 40, 0, 0.3, 5000.0, 5, 0, 1, 0, 0);
            };
        }

        @Bean
        @Primary
        RepaymentHistoryClient stubRepaymentHistoryClient() {
            return applicantId -> new RepaymentHistory(0, 0, 0, 1.0);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetCounter() {
        TestOverrides.bureauCallCount.set(0);
    }

    private String scoreRequestPayload(String applicantId, String amount) {
        return """
                {"applicantId":"%s","amount":%s,"merchantCategory":"general","requestedAt":"%s"}
                """.formatted(applicantId, amount, Instant.now());
    }

    @Test
    void score_returnsApproveDecision_usingTheRealOnnxModel() throws Exception {
        mockMvc.perform(post("/internal/score")
                        .contentType("application/json")
                        .content(scoreRequestPayload(UUID.randomUUID().toString(), "199.99")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVE"))
                .andExpect(jsonPath("$.scoreFactors.length()").value(4));
    }

    @Test
    void score_cachesRepeatedIdenticalRequests() throws Exception {
        String payload = scoreRequestPayload(UUID.randomUUID().toString(), "50.00");

        mockMvc.perform(post("/internal/score").contentType("application/json").content(payload))
                .andExpect(status().isOk());
        mockMvc.perform(post("/internal/score").contentType("application/json").content(payload))
                .andExpect(status().isOk());

        assertThat(TestOverrides.bureauCallCount.get()).isEqualTo(1);
    }

    @Test
    void score_rejectsInvalidPayload() throws Exception {
        mockMvc.perform(post("/internal/score").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void creditLimit_returnsTheLimitAndBand_usingTheRealOnnxModel() throws Exception {
        // the same stub profile scores APPROVE at 199.99 above, so it is LOW: min(5000 / 2, 1500)
        mockMvc.perform(get("/internal/credit-limit/{id}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(1500.00))
                .andExpect(jsonPath("$.band").value("LOW"));
    }

    @Test
    void creditLimit_isCachedPerApplicant() throws Exception {
        UUID applicant = UUID.randomUUID();
        mockMvc.perform(get("/internal/credit-limit/{id}", applicant)).andExpect(status().isOk());
        mockMvc.perform(get("/internal/credit-limit/{id}", applicant)).andExpect(status().isOk());

        assertThat(TestOverrides.bureauCallCount.get()).isEqualTo(1);
    }
}
