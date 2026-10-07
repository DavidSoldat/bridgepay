package com.bridgepay.creditrisk.web;

import org.junit.jupiter.api.Nested;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real SecurityConfig (no local profile), real model and baseline. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ModelControllerIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @TestConfiguration
    static class NoKeycloak {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none").claim("sub", "unused")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void opsGetsTheTrainingBaselineWithThresholds() throws Exception {
        mockMvc.perform(get("/api/v1/model").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelVersion").isString())
                .andExpect(jsonPath("$.scoreBins.length()").value(10))
                .andExpect(jsonPath("$.thresholds.review").value(0.3))
                .andExpect(jsonPath("$.thresholds.decline").value(0.7));
    }

    @Test
    void onlyOps() throws Exception {
        mockMvc.perform(get("/api/v1/model").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SHOPPER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/model")).andExpect(status().isUnauthorized());
    }

    /** A baseline written for another model (here: the toy fixture's coefficients file has no matching version). */
    @Nested
    @SpringBootTest(properties = "bridgepay.credit-risk-model.baseline-path=classpath:fixtures/coefficients.json")
    @AutoConfigureMockMvc
    class StaleBaseline {

        @Autowired
        MockMvc staleMvc;

        @Test
        void isServiceUnavailable() throws Exception {
            staleMvc.perform(get("/api/v1/model").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS"))))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("BASELINE_UNAVAILABLE"));
        }
    }
}
