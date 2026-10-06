package com.bridgepay.application;

import com.bridgepay.application.client.CreditLimit;
import com.bridgepay.application.client.CreditLimitClient;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Postgres + Kafka; the engine's score and limit are switchable stubs (same pattern as SpendingLimitIntegrationTest). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ApplicantOverviewIntegrationTest.TestOverrides.class})
class ApplicantOverviewIntegrationTest {

    static final AtomicReference<Optional<CreditLimit>> LIMIT = new AtomicReference<>();
    static final AtomicReference<Boolean> TX_DURING_LIMIT_CALL = new AtomicReference<>();

    @TestConfiguration
    static class TestOverrides {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none").claim("sub", "unused")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        }

        @Bean
        @Primary
        CreditRiskClient approveEverything() {
            return request -> new ScoreResult(0.2, ScoreDecision.APPROVE, List.of(new ScoreResult.ScoreFactor("age", -0.1)));
        }

        @Bean
        @Primary
        CreditLimitClient switchableLimit() {
            return applicantId -> {
                TX_DURING_LIMIT_CALL.set(TransactionSynchronizationManager.isActualTransactionActive());
                return LIMIT.get();
            };
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    MerchantRepository merchantRepository;
    @Autowired
    JdbcTemplate jdbc;

    private UUID merchantId;
    private UUID shopper;
    private UUID other;

    @BeforeEach
    void setUp() {
        merchantId = merchantRepository.save(new Merchant("Overview Merchant", new BigDecimal("2.90"))).getId();
        shopper = UUID.randomUUID();
        other = UUID.randomUUID();
        LIMIT.set(Optional.of(new CreditLimit(new BigDecimal("600.00"), "LOW")));
    }

    private void checkout(UUID subject, String amount) throws Exception {
        mockMvc.perform(post("/api/v1/applications")
                        .with(jwt().jwt(j -> j.subject(subject.toString())))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"merchantId\":\"%s\",\"amount\":%s}".formatted(merchantId, amount)))
                .andExpect(status().isCreated());
    }

    private ResultActions asOps(String path) throws Exception {
        return mockMvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS"))));
    }

    @Test
    void applications_areThatShoppersOnly_newestFirst_withMerchantAndDecisionSource() throws Exception {
        checkout(shopper, "40.00");
        checkout(shopper, "60.00");
        checkout(other, "70.00");

        asOps("/api/v1/applications/applicants/" + shopper).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].amount").value(60.00))
                .andExpect(jsonPath("$.content[0].merchantName").value("Overview Merchant"))
                .andExpect(jsonPath("$.content[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.content[0].decisionSource").value("MODEL"))
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.content[1].amount").value(40.00));
    }

    @Test
    void applications_includeDemoRows_forAnExplicitShopper_whileTheQueueStillHidesThem() throws Exception {
        checkout(shopper, "40.00");
        jdbc.update("update application.applications set is_demo = true where applicant_id = ?", shopper);

        asOps("/api/v1/applications/applicants/" + shopper).andExpect(jsonPath("$.totalElements").value(1));
        asOps("/api/v1/applications?status=ALL&size=2000")
                .andExpect(jsonPath("$.content[?(@.applicantId == '%s')]".formatted(shopper)).isEmpty());
    }

    @Test
    void creditStanding_subtractsOutstanding_outsideATransaction() throws Exception {
        checkout(shopper, "150.00");

        asOps("/api/v1/applications/applicants/" + shopper + "/credit-standing").andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(600.00))
                .andExpect(jsonPath("$.outstanding").value(150.00))
                .andExpect(jsonPath("$.available").value(450.00))
                .andExpect(jsonPath("$.band").value("LOW"));
        assertThat(TX_DURING_LIMIT_CALL.get()).isFalse();
    }

    @Test
    void creditStanding_withEngineDown_keepsOutstanding() throws Exception {
        checkout(shopper, "150.00");
        LIMIT.set(Optional.empty());

        asOps("/api/v1/applications/applicants/" + shopper + "/credit-standing").andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").doesNotExist())
                .andExpect(jsonPath("$.band").doesNotExist())
                .andExpect(jsonPath("$.outstanding").value(150.00));
    }

    @Test
    void bothEndpoints_areForbiddenForAShopper_evenForThemselves() throws Exception {
        for (String path : List.of("/api/v1/applications/applicants/" + shopper,
                "/api/v1/applications/applicants/" + shopper + "/credit-standing")) {
            mockMvc.perform(get(path).with(jwt().jwt(j -> j.subject(shopper.toString()))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void aMalformedApplicantId_isAValidationError() throws Exception {
        asOps("/api/v1/applications/applicants/not-a-uuid").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
}
