package com.bridgepay.application;

import com.bridgepay.application.client.CreditRiskClient;
import com.bridgepay.application.client.ScoreDecision;
import com.bridgepay.application.client.ScoreResult;
import com.bridgepay.application.domain.Merchant;
import com.bridgepay.application.repository.MerchantRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class CreditApplicationControllerIntegrationTest {

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

        // Real Credit Risk Engine doesn't exist yet - this stub always
        // approves, so the integration test can exercise the full happy path
        // (payout + outbox event creation) deterministically.
        @Bean
        @Primary
        CreditRiskClient stubCreditRiskClient() {
            return request -> new ScoreResult(0.1, ScoreDecision.APPROVE, List.of());
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
        Merchant merchant = merchantRepository.save(new Merchant("Test Merchant", new BigDecimal("3.50")));
        merchantId = merchant.getId();
    }

    private String checkoutPayload(String merchantId, String amount) throws Exception {
        return objectMapper.writeValueAsString(Map.of("merchantId", merchantId, "amount", amount));
    }

    @Test
    void checkout_approvesAndCreatesInstallmentPlan() throws Exception {
        String applicantId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/applications")
                        .with(jwt().jwt(j -> j.subject(applicantId)))
                        .header("Idempotency-Key", "idem-1")
                        .contentType("application/json")
                        .content(checkoutPayload(merchantId.toString(), "200.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.installmentCount").value(4))
                .andExpect(jsonPath("$.installmentAmount").value(50.00))
                .andExpect(jsonPath("$.applicantId").value(applicantId))
                .andExpect(jsonPath("$.merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.amount").value(200.00))
                .andExpect(jsonPath("$.riskScore").value(0.1))
                .andExpect(jsonPath("$.scoreFactors").isArray());
    }

    @Test
    void checkout_replaysCachedResponse_onRetryWithSameIdempotencyKey() throws Exception {
        String payload = checkoutPayload(merchantId.toString(), "80.00");
        String subject = UUID.randomUUID().toString();

        String firstResponse = mockMvc.perform(post("/api/v1/applications")
                        .with(jwt().jwt(j -> j.subject(subject)))
                        .header("Idempotency-Key", "idem-2")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/api/v1/applications")
                        .with(jwt().jwt(j -> j.subject(subject)))
                        .header("Idempotency-Key", "idem-2")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(content -> {
                    String replayed = content.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(replayed).isEqualTo(firstResponse);
                });
    }

    @Test
    void checkout_rejectsMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/applications")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))
                        .contentType("application/json")
                        .content(checkoutPayload(merchantId.toString(), "80.00")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void opsEndpoints_rejectShoppersWithoutOpsRole() throws Exception {
        mockMvc.perform(get("/api/v1/applications")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void opsEndpoints_allowUsersWithOpsRole() throws Exception {
        mockMvc.perform(get("/api/v1/applications")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                                        .claim("realm_access", Map.of("roles", List.of("ops"))))
                                .authorities(new SimpleGrantedAuthority("ROLE_OPS"))))
                .andExpect(status().isOk());
    }

    @Test
    void opsEndpoints_filterByStatus_andSupportAllAsNoFilter() throws Exception {
        var opsUser = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("realm_access", Map.of("roles", List.of("ops"))))
                .authorities(new SimpleGrantedAuthority("ROLE_OPS"));

        mockMvc.perform(post("/api/v1/applications")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))
                        .header("Idempotency-Key", "idem-status-filter")
                        .contentType("application/json")
                        .content(checkoutPayload(merchantId.toString(), "40.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/applications").param("status", "APPROVED").with(opsUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("APPROVED"));

        mockMvc.perform(get("/api/v1/applications").param("status", "DECLINED").with(opsUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        mockMvc.perform(get("/api/v1/applications").param("status", "ALL").with(opsUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").exists());
    }

    @Test
    void listMine_returnsOnlyTheAuthenticatedShoppersOwnApplications() throws Exception {
        String subject = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/applications")
                        .with(jwt().jwt(j -> j.subject(subject)))
                        .header("Idempotency-Key", "idem-mine-1")
                        .contentType("application/json")
                        .content(checkoutPayload(merchantId.toString(), "60.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/applications")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))
                        .header("Idempotency-Key", "idem-mine-other")
                        .contentType("application/json")
                        .content(checkoutPayload(merchantId.toString(), "60.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/applications/me").with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].applicantId").value(subject));
    }

    @Test
    void opsEndpoints_rejectUnknownStatus() throws Exception {
        mockMvc.perform(get("/api/v1/applications")
                        .param("status", "NOT_A_STATUS")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                                        .claim("realm_access", Map.of("roles", List.of("ops"))))
                                .authorities(new SimpleGrantedAuthority("ROLE_OPS"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
}
