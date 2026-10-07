package com.bridgepay.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ModelMonitoringIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bridgepay").withUsername("test").withPassword("test");

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
            return token -> Jwt.withTokenValue(token).header("alg", "none").claim("sub", "unused")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private static final String URL = "/api/v1/applications/model-monitoring";
    private static final String MERCHANT = "00000000-0000-7000-8000-000000000001";

    private static RequestPostProcessor as(String role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static String factors(double age, double debt) {
        return "[{\"feature\":\"age\",\"contribution\":" + age + "},{\"feature\":\"debtRatio\",\"contribution\":" + debt + "}]";
    }

    /** createdDaysAgo/decidedDaysAgo are whole days before now (UTC). */
    private void app(String status, Double score, String factors, String source, int createdDaysAgo, int decidedDaysAgo) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO application.applications (id, applicant_id, merchant_id, amount, status, risk_score,
                  score_factors, decision_source, created_at, decision_at)
                VALUES (?, ?, ?::uuid, 50.00, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), UUID.randomUUID(), MERCHANT, status, score, factors, source,
                Timestamp.from(now.minus(createdDaysAgo, ChronoUnit.DAYS)),
                Timestamp.from(now.minus(decidedDaysAgo, ChronoUnit.DAYS)));
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM application.merchant_payouts");
        jdbc.update("DELETE FROM application.applications");
        // drift rows, created in the last 7 days
        app("APPROVED", 0.12, factors(0.4, -0.1), "MODEL", 1, 1);
        app("APPROVED", 0.18, factors(0.2, 0.1), "MODEL", 2, 2);
        app("DECLINED", 0.85, factors(0.6, 0.3).replace("]", ",{\"feature\":\"priorDefault\",\"contribution\":3.0}]"), "MODEL", 3, 3);
        app("MANUAL_REVIEW", 0.45, "[]", null, 1, 1);           // scored, no factors
        app("MANUAL_REVIEW", 0.50, "not json", null, 1, 1);     // scored, unreadable factors: skipped, never a 500
        app("MANUAL_REVIEW", null, null, null, 1, 1);           // unscored: not in drift at all
        // performance rows, decided in the last 7 days (created earlier)
        app("COMPLETED", 0.15, factors(0, 0), "MODEL", 40, 2);
        app("DEFAULTED", 0.19, factors(0, 0), "MODEL", 40, 2);
        app("COMPLETED", 0.42, factors(0, 0), "OPS", 40, 3);    // ops approved, lean approve -> agreed
        app("DECLINED", 0.61, factors(0, 0), "OPS", 40, 3);     // ops declined, lean decline -> agreed
        app("APPROVED", 0.66, factors(0, 0), "OPS", 40, 3);     // ops approved, lean decline -> disagreed
        app("APPROVED", null, null, "OPS", 40, 3);              // held (engine down): not counted at all
        app("CANCELLED", 0.35, factors(0, 0), "OPS", 40, 3);    // ops approved, order later cancelled -> agreed
        app("REFUNDED", 0.10, factors(0, 0), "MODEL", 40, 2);   // not an outcome
        // outside the 7-day window
        app("DEFAULTED", 0.91, factors(9, 9), "MODEL", 60, 50);
    }

    @Test
    void driftOverCreatedAndPerformanceOverDecided() throws Exception {
        mockMvc.perform(get(URL).param("days", "7").param("tz", "UTC").with(as("OPS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.drift.scored").value(5))
                .andExpect(jsonPath("$.drift.scoreBins", hasSize(10)))
                .andExpect(jsonPath("$.drift.scoreBins[1].count").value(2))
                .andExpect(jsonPath("$.drift.scoreBins[4].count").value(1))
                .andExpect(jsonPath("$.drift.scoreBins[5].count").value(1))
                // 0.85 includes priorDefault +3.0: the model alone scored sigmoid(logit(0.85) - 3) = 0.22
                .andExpect(jsonPath("$.drift.scoreBins[8].count").value(0))
                .andExpect(jsonPath("$.drift.scoreBins[2].count").value(1))
                .andExpect(jsonPath("$.drift.factors[0].feature").value("priorDefault"))
                .andExpect(jsonPath("$.drift.factors[0].fireRate").value(closeTo(0.2, 1e-9)))
                .andExpect(jsonPath("$.drift.factors[?(@.feature=='age')].count").value(contains(3)))
                .andExpect(jsonPath("$.drift.factors[?(@.feature=='age')].meanContribution").value(contains(closeTo(0.4, 1e-9))))
                .andExpect(jsonPath("$.drift.factors[?(@.feature=='age')].fireRate").value(contains(closeTo(0.6, 1e-9))))
                .andExpect(jsonPath("$.performance.finished").value(3))
                .andExpect(jsonPath("$.performance.outcomeBins", hasSize(10)))
                .andExpect(jsonPath("$.performance.outcomeBins[1].finished").value(2))
                .andExpect(jsonPath("$.performance.outcomeBins[1].defaulted").value(1))
                .andExpect(jsonPath("$.performance.outcomeBins[4].finished").value(1))
                .andExpect(jsonPath("$.performance.reviews.decided").value(4))
                .andExpect(jsonPath("$.performance.reviews.agreedWithModel").value(3))
                .andExpect(jsonPath("$.performance.reviews.opsApproved.finished").value(1))
                .andExpect(jsonPath("$.performance.reviews.opsApproved.defaulted").value(0))
                .andExpect(jsonPath("$.performance.reviews.modelApproved.finished").value(2))
                .andExpect(jsonPath("$.performance.reviews.modelApproved.defaulted").value(1));
    }

    @Test
    void longerPeriodIncludesOlderRows() throws Exception {
        mockMvc.perform(get(URL).param("days", "90").param("tz", "UTC").with(as("OPS")))
                .andExpect(jsonPath("$.drift.scored").value(13))
                .andExpect(jsonPath("$.performance.finished").value(4))
                .andExpect(jsonPath("$.performance.outcomeBins[9].defaulted").value(1));
    }

    /**
     * Bins compare against a model-only training histogram, so policy-rule pushes are taken back out of the decision
     * score: model 0.15 + amountToIncome (+1.0 log-odds) is stored as 0.3242 but belongs in the 0.1-0.2 bin.
     */
    @Test
    void binsUseTheModelScoreWithoutPolicyRules() throws Exception {
        jdbc.update("DELETE FROM application.applications");
        String withRule = "[{\"feature\":\"age\",\"contribution\":0.1},{\"feature\":\"amountToIncome\",\"contribution\":1.0}]";
        app("COMPLETED", 0.3242, withRule, "MODEL", 1, 1);
        app("APPROVED", 0.3242, withRule, "MODEL", 1, 1);
        mockMvc.perform(get(URL).param("days", "7").param("tz", "UTC").with(as("OPS")))
                .andExpect(jsonPath("$.drift.scoreBins[1].count").value(2))
                .andExpect(jsonPath("$.drift.scoreBins[3].count").value(0))
                .andExpect(jsonPath("$.performance.outcomeBins[1].finished").value(1));
    }

    @Test
    void onlyOpsAndValidParams() throws Exception {
        mockMvc.perform(get(URL).param("days", "7").with(as("MERCHANT"))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).param("days", "7").with(as("SHOPPER"))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).param("days", "7")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).param("days", "14").with(as("OPS")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        mockMvc.perform(get(URL).param("days", "7").param("tz", "+02:00").with(as("OPS")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
}
