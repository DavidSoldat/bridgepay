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
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ApplicantControllerIntegrationTest {

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
    static class StubJwtDecoderConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(60))
                    .build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String signupPayload(String email) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "firstName", "Ana",
                "lastName", "Doe",
                "dateOfBirth", "1995-04-12",
                "email", email,
                "phone", "+38765123456"
        ));
    }

    @Test
    void signUpThenFetchProfile_roundTripsCorrectly() throws Exception {
        mockMvc.perform(post("/api/v1/applicants")
                        .with(jwt().jwt(j -> j.subject("kc-123")))
                        .contentType("application/json")
                        .content(signupPayload("ana@example.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("ana@example.com"));

        mockMvc.perform(get("/api/v1/applicants/me")
                        .with(jwt().jwt(j -> j.subject("kc-123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Ana"));
    }

    @Test
    void signUp_rejectsInvalidPayload() throws Exception {
        String invalidBody = objectMapper.writeValueAsString(Map.of(
                "firstName", "",
                "lastName", "Doe",
                "dateOfBirth", "1995-04-12",
                "email", "not-an-email",
                "phone", "+38765123456"
        ));

        mockMvc.perform(post("/api/v1/applicants")
                        .with(jwt().jwt(j -> j.subject("kc-456")))
                        .contentType("application/json")
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signUp_rejectsDuplicateSubject() throws Exception {
        mockMvc.perform(post("/api/v1/applicants")
                        .with(jwt().jwt(j -> j.subject("kc-789")))
                        .contentType("application/json")
                        .content(signupPayload("first@example.com")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/applicants")
                        .with(jwt().jwt(j -> j.subject("kc-789")))
                        .contentType("application/json")
                        .content(signupPayload("second@example.com")))
                .andExpect(status().isConflict());
    }

    @Test
    void endpoints_rejectUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/api/v1/applicants/me"))
                .andExpect(status().isUnauthorized());
    }
}
