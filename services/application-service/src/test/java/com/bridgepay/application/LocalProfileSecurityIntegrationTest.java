package com.bridgepay.application;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The local profile is permitAll with no real JWT decoder, so requests never
 * carry a real bearer token - these tests deliberately send none. They exist
 * to prove @AuthenticationPrincipal Jwt-dependent endpoints work under
 * docker-compose without Keycloak, per LocalDevSecurityConfig's stated
 * purpose, instead of NPE-ing on a null principal. Ops endpoints stay
 * @PreAuthorize-gated and must still reject under local, per SecurityConfig's
 * documented convention - this class also pins that down as a regression
 * guard, not just the happy path.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LocalProfileSecurityIntegrationTest {

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
        // Real Credit Risk Engine doesn't exist yet - this stub always
        // approves, so the checkout path is deterministic here too.
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

    private String merchantId;

    @BeforeEach
    void seedMerchant() {
        merchantId = merchantRepository.save(new Merchant("Test Merchant", new BigDecimal("3.50")))
                .getId().toString();
    }

    @Test
    void checkoutThenGet_worksWithoutARealJwt() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of("merchantId", merchantId, "amount", "200.00"));

        String body = mockMvc.perform(post("/api/v1/applications")
                        .header("Idempotency-Key", "local-idem-1")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn().getResponse().getContentAsString();
        String applicationId = objectMapper.readTree(body).get("applicationId").asText();

        mockMvc.perform(get("/api/v1/applications/{id}", applicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(applicationId));
    }

    @Test
    void opsEndpoints_stillRejectRequestsUnderLocalProfile() throws Exception {
        mockMvc.perform(get("/api/v1/applications"))
                .andExpect(status().isForbidden());
    }

    @Test
    void checkout_usesTheRealSubjectFromABearerTokenWhenOnePresent() throws Exception {
        String realSubject = UUID.randomUUID().toString();
        String payload = objectMapper.writeValueAsString(Map.of("merchantId", merchantId, "amount", "150.00"));

        mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + unsignedTestToken(realSubject))
                        .header("Idempotency-Key", "local-idem-real-subject")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applicantId").value(realSubject));
    }

    private static String unsignedTestToken(String subject) {
        String header = base64Url("{\"alg\":\"none\"}");
        String payloadJson = base64Url("{\"sub\":\"" + subject + "\"}");
        return header + "." + payloadJson + ".";
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
