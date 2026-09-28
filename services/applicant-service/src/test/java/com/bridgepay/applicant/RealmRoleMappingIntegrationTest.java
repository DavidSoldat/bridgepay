package com.bridgepay.applicant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sends real bearer tokens through SecurityConfig's JwtAuthenticationConverter - the path production
 * (k8s, non-local profile) takes. Other tests inject authorities directly and never exercise it.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RealmRoleMappingIntegrationTest {

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
    static class KeycloakShapedTokens {
        /** "ops-token" carries Keycloak's realm_access.roles = [ops]; any other token carries no roles. */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                Jwt.Builder jwt = Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .claim("sub", UUID.randomUUID().toString())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60));
                if ("ops-token".equals(token)) {
                    jwt.claim("realm_access", Map.of("roles", List.of("ops")));
                }
                return jwt.build();
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aKeycloakTokenWithTheOpsRealmRole_passesTheOpsCheck() throws Exception {
        // Unknown subject: 404 proves the request got past @PreAuthorize (403 would mean the role wasn't mapped).
        mockMvc.perform(get("/api/v1/ops/applicants/{subject}", UUID.randomUUID())
                        .header("Authorization", "Bearer ops-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aKeycloakTokenWithoutTheOpsRealmRole_isForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/ops/applicants/{subject}", UUID.randomUUID())
                        .header("Authorization", "Bearer shopper-token"))
                .andExpect(status().isForbidden());
    }
}
