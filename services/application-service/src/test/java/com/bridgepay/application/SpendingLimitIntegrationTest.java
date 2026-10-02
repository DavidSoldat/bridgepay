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

/** Real Postgres + Kafka; the Credit Risk Engine's score and limit are switchable stubs. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SpendingLimitIntegrationTest.TestOverrides.class})
class SpendingLimitIntegrationTest {

    static final AtomicReference<Optional<CreditLimit>> LIMIT = new AtomicReference<>();
    static final AtomicReference<ScoreDecision> DECISION = new AtomicReference<>();
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
        CreditRiskClient switchableScore() {
            return request -> new ScoreResult(0.2, DECISION.get(), List.of(new ScoreResult.ScoreFactor("age", -0.1)));
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

    @BeforeEach
    void setUp() {
        merchantId = merchantRepository.save(new Merchant("Limit Merchant", new BigDecimal("2.90"))).getId();
        shopper = UUID.randomUUID();
        LIMIT.set(Optional.of(new CreditLimit(new BigDecimal("600.00"), "LOW")));
        DECISION.set(ScoreDecision.APPROVE);
    }

    private ResultActions checkout(UUID subject, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/applications")
                .with(jwt().jwt(j -> j.subject(subject.toString())))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"merchantId\":\"%s\",\"amount\":%s}".formatted(merchantId, amount)));
    }

    private ResultActions limitOf(UUID subject) throws Exception {
        return mockMvc.perform(get("/api/v1/applications/me/credit-limit")
                .with(jwt().jwt(j -> j.subject(subject.toString()))));
    }

    private int applicationsOf(UUID subject) {
        return jdbc.queryForObject("select count(*) from application.applications where applicant_id = ?",
                Integer.class, subject);
    }

    @Test
    void creditLimit_withNothingOutstanding_isTheWholeLimit() throws Exception {
        limitOf(shopper).andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(600.00))
                .andExpect(jsonPath("$.outstanding").value(0.00))
                .andExpect(jsonPath("$.available").value(600.00))
                .andExpect(jsonPath("$.band").value("LOW"));
    }

    /** The engine call is HTTP; holding a DB connection across it lets a slow engine drain the pool. */
    @Test
    void creditLimit_asksTheEngineOutsideADatabaseTransaction() throws Exception {
        limitOf(shopper).andExpect(status().isOk());

        assertThat(TX_DURING_LIMIT_CALL.get()).isFalse();
    }

    @Test
    void creditLimit_subtractsApprovedOrders() throws Exception {
        checkout(shopper, "150.00").andExpect(status().isCreated());

        limitOf(shopper).andExpect(jsonPath("$.outstanding").value(150.00))
                .andExpect(jsonPath("$.available").value(450.00));
    }

    @Test
    void creditLimit_countsInReviewOrders() throws Exception {
        DECISION.set(ScoreDecision.MANUAL_REVIEW);
        checkout(shopper, "200.00").andExpect(status().isCreated());

        limitOf(shopper).andExpect(jsonPath("$.available").value(400.00));
    }

    @Test
    void creditLimit_isPerShopper() throws Exception {
        checkout(shopper, "150.00").andExpect(status().isCreated());

        limitOf(UUID.randomUUID()).andExpect(jsonPath("$.outstanding").value(0.00));
    }

    @Test
    void creditLimit_whenEngineDown_returnsNullLimitWithOutstanding() throws Exception {
        checkout(shopper, "150.00").andExpect(status().isCreated());
        LIMIT.set(Optional.empty());

        limitOf(shopper).andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").doesNotExist())
                .andExpect(jsonPath("$.available").doesNotExist())
                .andExpect(jsonPath("$.band").doesNotExist())
                .andExpect(jsonPath("$.outstanding").value(150.00));
    }

    @Test
    void checkout_overAvailable_is422_andCreatesNothing() throws Exception {
        checkout(shopper, "150.00").andExpect(status().isCreated());

        checkout(shopper, "450.01").andExpect(status().is(422))
                .andExpect(jsonPath("$.error").value("OVER_LIMIT"))
                .andExpect(jsonPath("$.message").value("This order is $450.01; you have $450.00 available."));

        assertThat(applicationsOf(shopper)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from application.idempotency_keys where applicant_id = ?",
                Integer.class, shopper)).isEqualTo(1);
    }

    @Test
    void checkout_atExactlyAvailable_isAllowed() throws Exception {
        checkout(shopper, "600.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void checkout_withAZeroLimit_isRefused() throws Exception {
        LIMIT.set(Optional.of(new CreditLimit(new BigDecimal("0.00"), "HIGH")));

        checkout(shopper, "10.00").andExpect(status().is(422));
    }

    @Test
    void checkout_whenTheLimitCannotBeChecked_neverAutoApproves() throws Exception {
        LIMIT.set(Optional.empty());

        checkout(shopper, "150.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.scoreFactors[1].feature").value("creditLimitUnavailable"));
    }

    @Test
    void checkout_whenTheLimitCannotBeChecked_keepsADecline() throws Exception {
        LIMIT.set(Optional.empty());
        DECISION.set(ScoreDecision.DECLINE);

        checkout(shopper, "150.00").andExpect(jsonPath("$.status").value("DECLINED"));
    }
}
