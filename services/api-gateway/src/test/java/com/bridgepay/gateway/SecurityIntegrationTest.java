package com.bridgepay.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    static WireMockServer wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @BeforeAll
    static void startWireMock() {
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("bridgepay.applicant-service.base-url", wireMock::baseUrl);
        registry.add("bridgepay.application-service.base-url", wireMock::baseUrl);
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @TestConfiguration
    static class TestOverrides {
        // No real Keycloak in tests - same stub-JwtDecoder pattern already
        // used by Application Service's own integration tests.
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
    MockMvc mockMvc;

    @Test
    void rejectsARequestWithNoToken() throws Exception {
        mockMvc.perform(post("/api/v1/applicants").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsARequestWithAValidToken_andRoutesIt() throws Exception {
        wireMock.stubFor(WireMock.post(urlEqualTo("/api/v1/applicants")).willReturn(okJson("{}")));

        mockMvc.perform(post("/api/v1/applicants")
                        .with(jwt())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());
    }
}
