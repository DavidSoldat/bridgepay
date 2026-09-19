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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    private RequestPostProcessor merchantJwt(UUID merchantId) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("merchantId", merchantId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"));
    }

    private void checkout(UUID merchantId, String amount) throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of("merchantId", merchantId.toString(), "amount", amount));
        mockMvc.perform(post("/api/v1/applications")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isCreated());
    }

    @Test
    void merchantSeesOwnPayouts() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("3.50")));
        checkout(merchant.getId(), "100.00");

        mockMvc.perform(get("/api/v1/merchants/{id}/payouts", merchant.getId())
                        .with(merchantJwt(merchant.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].amount").value(100.00))
                .andExpect(jsonPath("$.content[0].status").value("PAID"));
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
    void nonMerchantRoleIsBlocked() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant A", new BigDecimal("3.50")));

        mockMvc.perform(get("/api/v1/merchants/{id}/payouts", merchant.getId())
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }
}
