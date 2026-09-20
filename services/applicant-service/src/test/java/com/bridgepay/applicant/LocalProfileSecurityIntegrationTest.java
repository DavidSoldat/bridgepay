package com.bridgepay.applicant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The local profile is permitAll with no real JWT decoder, so requests never
 * carry a real bearer token - these tests deliberately send none. They exist
 * to prove @AuthenticationPrincipal Jwt-dependent endpoints work under
 * docker-compose without Keycloak, per LocalDevSecurityConfig's stated
 * purpose, instead of NPE-ing on a null principal.
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void signUpThenFetchProfile_worksWithoutARealJwt() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "firstName", "Local",
                "lastName", "Dev",
                "dateOfBirth", "1995-04-12",
                "email", "local-dev@example.com",
                "phone", "+38765123456"
        ));

        mockMvc.perform(post("/api/v1/applicants")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("local-dev@example.com"));

        mockMvc.perform(get("/api/v1/applicants/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("local-dev@example.com"));
    }
}
