package com.bridgepay.applicant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    void signUp_rejectsAnEmailWithoutADotInTheDomain() throws Exception {
        // Paddle rejects such addresses when creating the customer, so the approved order's plan could never be made
        mockMvc.perform(post("/api/v1/applicants")
                        .with(jwt().jwt(j -> j.subject("kc-no-tld")))
                        .contentType("application/json")
                        .content(signupPayload("ana@example")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("email")));
    }

    @Test
    void signUp_rejectsAnApplicantYoungerThan18() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "firstName", "Young",
                "lastName", "Shopper",
                "dateOfBirth", java.time.LocalDate.now().minusYears(18).plusDays(1).toString(),
                "email", "young@example.com",
                "phone", "+38765123456"
        ));

        mockMvc.perform(post("/api/v1/applicants")
                        .with(jwt().jwt(j -> j.subject("kc-too-young")))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("at least 18")));
    }

    @Test
    void signUp_acceptsAnApplicantTurning18Today() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "firstName", "Birthday",
                "lastName", "Shopper",
                "dateOfBirth", java.time.LocalDate.now().minusYears(18).toString(),
                "email", "birthday@example.com",
                "phone", "+38765123456"
        ));

        mockMvc.perform(post("/api/v1/applicants")
                        .with(jwt().jwt(j -> j.subject("kc-turning-18")))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated());
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

    /**
     * The internal endpoints are keyed by the Keycloak subject - the
     * "applicantId" every other service uses (Application Service stores
     * jwt.getSubject() as it; Repayment Reconciliation calls these with it).
     */
    @Test
    void internalEndpoints_areKeyedByTheKeycloakSubject_andReachableWithoutAJwt() throws Exception {
        String subject = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/applicants")
                        .with(jwt().jwt(j -> j.subject(subject)))
                        .contentType("application/json")
                        .content(signupPayload("internal@example.com")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/internal/applicants/{id}", subject))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(subject))
                .andExpect(jsonPath("$.email").value("internal@example.com"))
                .andExpect(jsonPath("$.paddleCustomerId").doesNotExist());

        mockMvc.perform(patch("/internal/applicants/{id}/paddle-customer", subject)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("paddleCustomerId", "ctm_01abc"))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/internal/applicants/{id}", subject))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paddleCustomerId").value("ctm_01abc"));
    }

    @Test
    void internalGet_doesNotResolveApplicantServicesOwnDatabaseId() throws Exception {
        String body = mockMvc.perform(post("/api/v1/applicants")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))
                        .contentType("application/json")
                        .content(signupPayload("dbid@example.com")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String databaseId = objectMapper.readTree(body).get("id").asText();

        mockMvc.perform(get("/internal/applicants/{id}", databaseId))
                .andExpect(status().isNotFound());
    }

    @Test
    void internalGet_returnsNotFound_whenNoApplicantHasThatSubject() throws Exception {
        mockMvc.perform(get("/internal/applicants/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void opsLookup_returnsTheShopperBySubject_forTheOpsRole() throws Exception {
        String subject = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/applicants")
                        .with(jwt().jwt(j -> j.subject(subject)))
                        .contentType("application/json")
                        .content(signupPayload("ops-lookup@example.com")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/ops/applicants/{subject}", subject)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(subject))
                .andExpect(jsonPath("$.firstName").value("Ana"))
                .andExpect(jsonPath("$.lastName").value("Doe"))
                .andExpect(jsonPath("$.email").value("ops-lookup@example.com"))
                .andExpect(jsonPath("$.phone").value("+38765123456"))
                .andExpect(jsonPath("$.dateOfBirth").value("1995-04-12"))
                .andExpect(jsonPath("$.paddleCustomerId").doesNotExist());
    }

    @Test
    void opsLookup_isForbiddenWithoutTheOpsRole() throws Exception {
        mockMvc.perform(get("/api/v1/ops/applicants/{subject}", UUID.randomUUID())
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void opsLookup_returnsNotFound_forAnUnknownSubject() throws Exception {
        mockMvc.perform(get("/api/v1/ops/applicants/{subject}", UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS"))))
                .andExpect(status().isNotFound());
    }

    private String signupPayload(String email, String lastName) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "firstName", "Ana", "lastName", lastName, "dateOfBirth", "1995-04-12",
                "email", email, "phone", "+38765123456"));
    }

    private void signUp(String email, String lastName) throws Exception {
        mockMvc.perform(post("/api/v1/applicants")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))
                        .contentType("application/json")
                        .content(signupPayload(email, lastName)))
                .andExpect(status().isCreated());
    }

    private ResultActions search(String q) throws Exception {
        return mockMvc.perform(get("/api/v1/ops/applicants").param("q", q)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS"))));
    }

    @Test
    void search_matchesEmailAndFullName_caseInsensitive_newestFirst() throws Exception {
        String tag = "Qx" + UUID.randomUUID().toString().substring(0, 6);
        signUp(tag.toLowerCase() + "-one@example.com", "First" + tag);
        signUp(tag.toLowerCase() + "-two@example.com", "Second" + tag);

        search(tag.toUpperCase()).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].lastName").value("Second" + tag))
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.content[1].lastName").value("First" + tag));
        search("ana second" + tag).andExpect(jsonPath("$.totalElements").value(1));
        search(tag.toLowerCase() + "-one@").andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void search_treatsWildcardsLiterally() throws Exception {
        signUp("wild-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com", "Wild");

        search("%%").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        search("__").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void search_rejectsUnderTwoCharacters() throws Exception {
        search(" a ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/ops/applicants").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void search_isForbiddenWithoutTheOpsRole() throws Exception {
        mockMvc.perform(get("/api/v1/ops/applicants").param("q", "ana")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }
}