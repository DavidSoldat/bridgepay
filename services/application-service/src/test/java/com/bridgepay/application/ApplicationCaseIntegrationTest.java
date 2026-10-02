package com.bridgepay.application;

import java.util.Optional;
import com.bridgepay.application.client.CreditLimitClient;
import com.bridgepay.application.client.CreditLimit;
import com.bridgepay.application.client.CreditRiskClient;
import com.bridgepay.application.client.ScoreDecision;
import com.bridgepay.application.client.ScoreResult;
import com.bridgepay.application.domain.Merchant;
import com.bridgepay.application.repository.MerchantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ApplicationCaseIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bridgepay")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @TestConfiguration
    static class TestOverrides {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(60))
                    .build();
        }

        /** 100.00 goes to manual review (with factors, like the real model); anything else is approved. */
        @Bean
        @Primary
        CreditLimitClient roomyLimit() {
            return applicantId -> Optional.of(new CreditLimit(new BigDecimal("100000.00"), "LOW"));
        }

        @Bean
        @Primary
        CreditRiskClient stubCreditRiskClient() {
            return request -> request.amount().compareTo(new BigDecimal("100.00")) == 0
                    ? new ScoreResult(0.5, ScoreDecision.MANUAL_REVIEW,
                            List.of(new ScoreResult.ScoreFactor("debtRatio", 0.3)))
                    : new ScoreResult(0.1, ScoreDecision.APPROVE, List.of());
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MerchantRepository merchantRepository;

    private UUID merchantId;

    @BeforeEach
    void seedMerchant() {
        merchantId = merchantRepository.save(new Merchant("Case Merchant", new BigDecimal("3.50"))).getId();
    }

    private static RequestPostProcessor ops() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "ops1")
                        .claim("realm_access", Map.of("roles", List.of("ops"))))
                .authorities(new SimpleGrantedAuthority("ROLE_OPS"));
    }

    private String checkout(String amount) throws Exception {
        String body = mockMvc.perform(post("/api/v1/applications")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("merchantId", merchantId.toString(), "amount", amount))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("applicationId").asText();
    }

    @Test
    void caseOfAModelApproval_showsTheMerchantPayoutAndAModelDecision() throws Exception {
        String id = checkout("200.00");

        mockMvc.perform(get("/api/v1/applications/{id}/case", id).with(ops()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decision.source").value("MODEL"))
                .andExpect(jsonPath("$.decision.decidedBy").value(nullValue()))
                .andExpect(jsonPath("$.merchant.name").value("Case Merchant"))
                .andExpect(jsonPath("$.merchant.feeRatePct").value(3.5))
                .andExpect(jsonPath("$.payout.amount").value(200.0))
                .andExpect(jsonPath("$.payout.feeAmount").value(7.0))
                .andExpect(jsonPath("$.payout.netAmount").value(193.0))
                .andExpect(jsonPath("$.payout.status").value("PENDING"));
    }

    @Test
    void caseAfterAnOpsDecision_recordsTheReviewerAndNote_andKeepsTheScoreFactors() throws Exception {
        String id = checkout("100.00");

        mockMvc.perform(get("/api/v1/applications/{id}/case", id).with(ops()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.decision").value(nullValue()))
                .andExpect(jsonPath("$.payout").value(nullValue()));

        mockMvc.perform(post("/api/v1/applications/{id}/review-decision", id).with(ops())
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\",\"reviewerNote\":\"  income verified  \"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/applications/{id}/case", id).with(ops()))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decision.source").value("OPS"))
                .andExpect(jsonPath("$.decision.decidedBy").value("ops1"))
                .andExpect(jsonPath("$.decision.reviewerNote").value("income verified"))
                .andExpect(jsonPath("$.scoreFactors[0].feature").value("debtRatio"))
                .andExpect(jsonPath("$.payout.status").value("PENDING"));
    }

    @Test
    void case_isForbiddenForAShopper() throws Exception {
        String id = checkout("200.00");

        mockMvc.perform(get("/api/v1/applications/{id}/case", id)
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void case_returnsNotFound_forAnUnknownApplication() throws Exception {
        mockMvc.perform(get("/api/v1/applications/{id}/case", UUID.randomUUID()).with(ops()))
                .andExpect(status().isNotFound());
    }

    @Test
    void reviewDecision_rejectsANoteLongerThan1000Characters() throws Exception {
        String id = checkout("100.00");

        mockMvc.perform(post("/api/v1/applications/{id}/review-decision", id).with(ops())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("decision", "APPROVE", "reviewerNote", "x".repeat(1001)))))
                .andExpect(status().isBadRequest());
    }
}
