package com.bridgepay.application;

import java.util.Optional;
import com.bridgepay.application.client.CreditLimitClient;
import com.bridgepay.application.client.CreditLimit;
import com.bridgepay.application.client.CreditRiskClient;
import com.bridgepay.application.client.ScoreDecision;
import com.bridgepay.application.client.ScoreResult;
import com.bridgepay.application.domain.Merchant;
import com.bridgepay.application.repository.MerchantRepository;
import com.bridgepay.application.repository.OutboxEventRepository;
import tools.jackson.databind.ObjectMapper;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class MerchantControllerIntegrationTest {

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

        @Bean
        @Primary
        CreditLimitClient roomyLimit() {
            return applicantId -> Optional.of(new CreditLimit(new BigDecimal("100000.00"), "LOW"));
        }

        @Bean
        @Primary
        CreditRiskClient stubCreditRiskClient() {
            // Amount picks the decision so tests can create every outcome through the real checkout path.
            return request -> {
                BigDecimal amount = request.amount();
                if (amount.compareTo(new BigDecimal("1000")) >= 0) {
                    return new ScoreResult(0.8, ScoreDecision.DECLINE, List.of());
                }
                if (amount.compareTo(new BigDecimal("500")) >= 0) {
                    return new ScoreResult(0.5, ScoreDecision.MANUAL_REVIEW, List.of());
                }
                return new ScoreResult(0.1, ScoreDecision.APPROVE, List.of());
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private RequestPostProcessor merchantJwt(UUID merchantId) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("merchantId", merchantId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"));
    }

    private UUID checkout(UUID merchantId, String amount) throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of("merchantId", merchantId.toString(), "amount", amount));
        String body = mockMvc.perform(post("/api/v1/applications")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("applicationId").asText());
    }

    @Test
    void merchantRefundsAnApprovedOrder_itGoesPendingAndARefundRequestIsQueued() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant R", new BigDecimal("2.90")));
        UUID applicationId = checkout(merchant.getId(), "100.00");

        mockMvc.perform(post("/api/v1/merchants/{id}/orders/{applicationId}/refund", merchant.getId(), applicationId)
                        .with(merchantJwt(merchant.getId())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REFUND_PENDING"))
                .andExpect(jsonPath("$.feeAmount").value(2.90));

        assertThat(outboxEventRepository.findAll()).filteredOn(e -> e.getTopic().equals("applications.refund-requested"))
                .filteredOn(e -> e.getPayload().contains(applicationId.toString()))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getPartitionKey()).isEqualTo(applicationId.toString());
                    assertThat(e.getPayload()).contains(merchant.getId().toString());
                });
    }

    @Test
    void aSecondRefundOfTheSameOrder_isAConflict_andQueuesNothingMore() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant R2", new BigDecimal("2.90")));
        UUID applicationId = checkout(merchant.getId(), "100.00");
        String url = "/api/v1/merchants/{id}/orders/{applicationId}/refund";
        mockMvc.perform(post(url, merchant.getId(), applicationId).with(merchantJwt(merchant.getId())))
                .andExpect(status().isAccepted());

        mockMvc.perform(post(url, merchant.getId(), applicationId).with(merchantJwt(merchant.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        assertThat(outboxEventRepository.findAll()).filteredOn(e -> e.getTopic().equals("applications.refund-requested"))
                .filteredOn(e -> e.getPayload().contains(applicationId.toString()))
                .hasSize(1);
    }

    @Test
    void ordersInReviewOrDeclined_cannotBeRefunded() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant R3", new BigDecimal("2.90")));
        UUID inReview = checkout(merchant.getId(), "600.00");
        UUID declined = checkout(merchant.getId(), "1500.00");

        for (UUID id : List.of(inReview, declined)) {
            mockMvc.perform(post("/api/v1/merchants/{id}/orders/{applicationId}/refund", merchant.getId(), id)
                            .with(merchantJwt(merchant.getId())))
                    .andExpect(status().isConflict());
        }
    }

    @Test
    void merchantCannotRefundAnotherMerchantsOrder() throws Exception {
        Merchant mine = merchantRepository.save(new Merchant("Mine", new BigDecimal("2.90")));
        Merchant theirs = merchantRepository.save(new Merchant("Theirs", new BigDecimal("2.90")));
        UUID theirOrder = checkout(theirs.getId(), "100.00");

        mockMvc.perform(post("/api/v1/merchants/{id}/orders/{applicationId}/refund", theirs.getId(), theirOrder)
                        .with(merchantJwt(mine.getId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/merchants/{id}/orders/{applicationId}/refund", mine.getId(), theirOrder)
                        .with(merchantJwt(mine.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void shoppersCannotRefund() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant R4", new BigDecimal("2.90")));
        UUID applicationId = checkout(merchant.getId(), "100.00");

        mockMvc.perform(post("/api/v1/merchants/{id}/orders/{applicationId}/refund", merchant.getId(), applicationId)
                        .with(jwt().jwt(j -> j.claim("merchantId", merchant.getId().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void salesRowsCarryTheFee() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant F", new BigDecimal("2.90")));
        checkout(merchant.getId(), "100.00");
        checkout(merchant.getId(), "1500.00");   // declined: no payout

        mockMvc.perform(get("/api/v1/merchants/{id}/sales", merchant.getId()).with(merchantJwt(merchant.getId())))
                .andExpect(jsonPath("$.content[0].feeAmount").doesNotExist())
                .andExpect(jsonPath("$.content[1].feeAmount").value(2.90));
    }

    @Test
    void merchantSeesOwnPayouts() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("3.50")));
        checkout(merchant.getId(), "100.00");

        mockMvc.perform(get("/api/v1/merchants/{id}/payouts", merchant.getId())
                        .with(merchantJwt(merchant.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].amount").value(100.00))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].paidAt").doesNotExist());
    }

    @Test
    void merchantIsBlockedFromAnotherMerchantsPayouts() throws Exception {
        Merchant merchantA = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("3.50")));
        Merchant merchantB = merchantRepository.save(new Merchant("Merchant B", new BigDecimal("3.50")));

        mockMvc.perform(get("/api/v1/merchants/{id}/payouts", merchantB.getId())
                        .with(merchantJwt(merchantA.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void merchantSeesOwnSalesNewestFirst_withoutShopperCreditData() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("3.50")));
        Merchant other = merchantRepository.save(new Merchant("Merchant B", new BigDecimal("3.50")));
        checkout(merchant.getId(), "100.00");
        checkout(merchant.getId(), "1500.00");
        checkout(other.getId(), "200.00");

        mockMvc.perform(get("/api/v1/merchants/{id}/sales", merchant.getId())
                        .with(merchantJwt(merchant.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].amount").value(1500.00))
                .andExpect(jsonPath("$.content[0].status").value("DECLINED"))
                .andExpect(jsonPath("$.content[1].amount").value(100.00))
                .andExpect(jsonPath("$.content[1].status").value("APPROVED"))
                .andExpect(jsonPath("$.content[1].installmentCount").value(4))
                .andExpect(jsonPath("$.content[1].installmentAmount").value(25.00))
                .andExpect(jsonPath("$.content[0].applicantId").doesNotExist())
                .andExpect(jsonPath("$.content[0].riskScore").doesNotExist())
                .andExpect(jsonPath("$.content[0].scoreFactors").doesNotExist());
    }

    @Test
    void salesCanBeFilteredByStatus() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("3.50")));
        checkout(merchant.getId(), "100.00");
        checkout(merchant.getId(), "600.00");
        checkout(merchant.getId(), "1500.00");

        mockMvc.perform(get("/api/v1/merchants/{id}/sales", merchant.getId())
                        .param("status", "MANUAL_REVIEW")
                        .with(merchantJwt(merchant.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("MANUAL_REVIEW"));
    }

    @Test
    void unknownSalesStatusIsRejectedWith400() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("3.50")));

        mockMvc.perform(get("/api/v1/merchants/{id}/sales", merchant.getId())
                        .param("status", "BOGUS")
                        .with(merchantJwt(merchant.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void merchantIsBlockedFromAnotherMerchantsSales() throws Exception {
        Merchant merchantA = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("3.50")));
        Merchant merchantB = merchantRepository.save(new Merchant("Merchant B", new BigDecimal("3.50")));

        mockMvc.perform(get("/api/v1/merchants/{id}/sales", merchantB.getId())
                        .with(merchantJwt(merchantA.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonMerchantRoleIsBlocked() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("3.50")));

        mockMvc.perform(get("/api/v1/merchants/{id}/payouts", merchant.getId())
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void dashboardReturnsThePeriodTotalsAndSeriesForTheMerchant() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("2.90")));
        checkout(merchant.getId(), "100.00");  // approved by the stub risk engine
        checkout(merchant.getId(), "1500.00"); // declined

        mockMvc.perform(get("/api/v1/merchants/{id}/dashboard", merchant.getId())
                        .param("days", "7").param("tz", "UTC")
                        .with(merchantJwt(merchant.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.bucket").value("DAY"))
                .andExpect(jsonPath("$.to").isString())
                .andExpect(jsonPath("$.series", hasSize(7)))
                .andExpect(jsonPath("$.series[6].checkouts").value(2))
                .andExpect(jsonPath("$.current.checkouts").value(2))
                .andExpect(jsonPath("$.current.approved").value(1))
                .andExpect(jsonPath("$.current.declined").value(1))
                .andExpect(jsonPath("$.current.approvalRate").value(0.5))
                .andExpect(jsonPath("$.current.approvedVolume").value(100.00))
                .andExpect(jsonPath("$.previous.checkouts").value(0))
                .andExpect(jsonPath("$.pendingPayout").value(97.10));
    }

    @Test
    void dashboardWithoutTimeZoneUsesUtcAndNinetyDaysAreWeekly() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("2.90")));

        mockMvc.perform(get("/api/v1/merchants/{id}/dashboard", merchant.getId())
                        .param("days", "90")
                        .with(merchantJwt(merchant.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucket").value("WEEK"))
                .andExpect(jsonPath("$.current.approvalRate").doesNotExist());
    }

    @Test
    void dashboardRejectsBadParametersWithTheSharedValidationError() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("2.90")));
        String url = "/api/v1/merchants/{id}/dashboard";

        mockMvc.perform(get(url, merchant.getId()).param("days", "14").with(merchantJwt(merchant.getId())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        mockMvc.perform(get(url, merchant.getId()).param("days", "abc").with(merchantJwt(merchant.getId())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        mockMvc.perform(get(url, merchant.getId()).with(merchantJwt(merchant.getId())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        mockMvc.perform(get(url, merchant.getId()).param("days", "7").param("tz", "Mars/Base")
                        .with(merchantJwt(merchant.getId())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        mockMvc.perform(get(url, merchant.getId()).param("days", "7").param("tz", "+02:00")
                        .with(merchantJwt(merchant.getId())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void merchantIsBlockedFromAnotherMerchantsDashboard() throws Exception {
        Merchant merchantA = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("2.90")));
        Merchant merchantB = merchantRepository.save(new Merchant("Merchant B", new BigDecimal("2.90")));

        mockMvc.perform(get("/api/v1/merchants/{id}/dashboard", merchantB.getId()).param("days", "7")
                        .with(merchantJwt(merchantA.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void dashboardNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/{id}/dashboard", UUID.randomUUID()).param("days", "7"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theOldSummaryEndpointIsGone() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("2.90")));

        mockMvc.perform(get("/api/v1/merchants/{id}/summary", merchant.getId())
                        .with(merchantJwt(merchant.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void merchantExportsSalesAsCsv_withPayoutColumns_andTheStatusFilter() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant CSV", new BigDecimal("2.90")));
        UUID approved = checkout(merchant.getId(), "100.00");
        UUID declined = checkout(merchant.getId(), "1500.00");

        String all = mockMvc.perform(get("/api/v1/merchants/{id}/sales/export", merchant.getId())
                        .with(merchantJwt(merchant.getId())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.matchesPattern("attachment; filename=\"bridgepay-sales-\\d{4}-\\d{2}-\\d{2}\\.csv\"")))
                .andReturn().getResponse().getContentAsString();

        String[] lines = all.split("\r\n");
        assertThat(lines[0]).isEqualTo("order_id,created_at,amount,installments,status,decided_at,fee,net,payout_status,paid_at");
        assertThat(lines).hasSize(3);
        assertThat(all).contains(approved + ",").contains(",100.00,4,APPROVED,").contains(",2.90,97.10,PENDING,");
        assertThat(all).contains(declined + ",").contains(",1500.00,,DECLINED,");

        String declinedOnly = mockMvc.perform(get("/api/v1/merchants/{id}/sales/export", merchant.getId())
                        .param("status", "DECLINED").with(merchantJwt(merchant.getId())))
                .andReturn().getResponse().getContentAsString();
        assertThat(declinedOnly.split("\r\n")).hasSize(2);
    }

    @Test
    void csvExportGuardsOwnershipAndStatus() throws Exception {
        Merchant mine = merchantRepository.save(new Merchant("Mine CSV", new BigDecimal("2.90")));
        Merchant theirs = merchantRepository.save(new Merchant("Theirs CSV", new BigDecimal("2.90")));

        mockMvc.perform(get("/api/v1/merchants/{id}/sales/export", theirs.getId()).with(merchantJwt(mine.getId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/merchants/{id}/sales/export", mine.getId()).param("status", "BOGUS")
                        .with(merchantJwt(mine.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
}
