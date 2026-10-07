package com.bridgepay.gateway.audit;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LocalProfileAuditIntegrationTest {

    static final String U = "0192a3b4-c5d6-7e8f-9a0b-1c2d3e4f5a6b";
    static WireMockServer wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @BeforeAll
    static void start() {
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("bridgepay.applicant-service.base-url", wireMock::baseUrl);
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    AuditEntryRepository repository;

    @Test
    void recordsTheRealCallerFromAnUnvalidatedBearerToken() throws Exception {
        repository.deleteAll();
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/api/v1/ops/applicants/" + U)).willReturn(okJson("{}")));
        String payload = "{\"sub\":\"ops-sub\",\"preferred_username\":\"ops1\",\"realm_access\":{\"roles\":[\"ops\"]}}";
        String token = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";

        mockMvc.perform(get("/api/v1/ops/applicants/" + U).header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        assertThat(repository.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getActorUsername()).isEqualTo("ops1");
            assertThat(e.getActorRole()).isEqualTo("OPS");
        });
    }
}
