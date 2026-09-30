package com.bridgepay.application;

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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class OpsDashboardControllerIntegrationTest {

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

    private static final String URL = "/api/v1/applications/dashboard";

    private static RequestPostProcessor opsJwt() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("realm_access", Map.of("roles", List.of("ops"))))
                .authorities(new SimpleGrantedAuthority("ROLE_OPS"));
    }

    private static RequestPostProcessor roleJwt(String role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void opsGetsThePlatformDashboard() throws Exception {
        mockMvc.perform(get(URL).param("days", "30").param("tz", "Europe/Belgrade").with(opsJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(30))
                .andExpect(jsonPath("$.bucket").value("DAY"))
                .andExpect(jsonPath("$.series", hasSize(30)))
                .andExpect(jsonPath("$.scoreHistogram", hasSize(10)))
                .andExpect(jsonPath("$.queue.inReview").isNumber())
                .andExpect(jsonPath("$.current.applications").isNumber())
                .andExpect(jsonPath("$.reviewers").isArray());
    }

    @Test
    void onlyOpsMaySeeIt() throws Exception {
        mockMvc.perform(get(URL).param("days", "7").with(roleJwt("MERCHANT"))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).param("days", "7").with(roleJwt("SHOPPER"))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).param("days", "7")).andExpect(status().isUnauthorized());
    }

    @Test
    void badParametersAreTheSharedValidationError() throws Exception {
        mockMvc.perform(get(URL).with(opsJwt()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        mockMvc.perform(get(URL).param("days", "14").with(opsJwt()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        mockMvc.perform(get(URL).param("days", "7").param("tz", "Mars/Base").with(opsJwt()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void anApplicationIdStillResolvesNextToTheDashboardPath() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO application.applications (id, applicant_id, merchant_id, amount, status)
                VALUES (?, ?, '00000000-0000-7000-8000-000000000001', 10.00, 'MANUAL_REVIEW')""",
                id, UUID.randomUUID());

        mockMvc.perform(get("/api/v1/applications/{id}", id).with(opsJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(id.toString()));
    }
}
