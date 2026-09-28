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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

    @Test
    void twoRealBearerTokensWithDifferentSubjects_areTreatedAsDifferentApplicants() throws Exception {
        String tokenA = unsignedTestToken("real-shopper-a");
        String tokenB = unsignedTestToken("real-shopper-b");

        mockMvc.perform(post("/api/v1/applicants")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "firstName", "Shopper", "lastName", "A", "dateOfBirth", "1995-04-12",
                                "email", "shopper-a@example.com", "phone", "+38765123456"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/applicants/me").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("shopper-a@example.com"));

        // A different real subject has no profile yet - proves it's not silently
        // collapsed onto tokenA's identity.
        mockMvc.perform(get("/api/v1/applicants/me").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    private static String unsignedTestToken(String subject) {
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url("{\"sub\":\"" + subject + "\"}");
        return header + "." + payload + ".";
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void opsLookup_underLocalProfile_allowsAnOpsBearerToken_andRejectsARequestWithoutOne() throws Exception {
        String shopper = unsignedTestToken("local-ops-lookup-shopper");
        mockMvc.perform(post("/api/v1/applicants")
                        .header("Authorization", "Bearer " + shopper)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "firstName", "Local", "lastName", "Shopper", "dateOfBirth", "1995-04-12",
                                "email", "local-ops-lookup@example.com", "phone", "+38765123456"))))
                .andExpect(status().isCreated());

        String opsToken = unsignedTokenWithPayload(
                "{\"sub\":\"local-ops-user\",\"preferred_username\":\"ops1\",\"realm_access\":{\"roles\":[\"ops\"]}}");
        mockMvc.perform(get("/api/v1/ops/applicants/{subject}", "local-ops-lookup-shopper")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("local-ops-lookup@example.com"));

        mockMvc.perform(get("/api/v1/ops/applicants/{subject}", "local-ops-lookup-shopper"))
                .andExpect(status().isForbidden());
    }

    private static String unsignedTokenWithPayload(String payloadJson) {
        return base64Url("{\"alg\":\"none\"}") + "." + base64Url(payloadJson) + ".";
    }
}