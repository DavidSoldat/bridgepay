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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class RoutingIntegrationTest {

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
        registry.add("bridgepay.repayment-reconciliation-service.base-url", wireMock::baseUrl);
    }

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @Test
    void routesApplicantPaths_toApplicantService() throws Exception {
        wireMock.stubFor(WireMock.post(urlEqualTo("/api/v1/applicants"))
                .willReturn(okJson("{\"id\":\"abc\"}")));

        mockMvc.perform(post("/api/v1/applicants")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"id\":\"abc\"}"));
    }

    @Test
    void routesApplicationPaths_toApplicationService_preservingQueryParams() throws Exception {
        wireMock.stubFor(WireMock.get(urlEqualTo("/api/v1/applications?status=MANUAL_REVIEW"))
                .willReturn(okJson("[]")));

        mockMvc.perform(get("/api/v1/applications").queryParam("status", "MANUAL_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void routesMerchantPaths_toApplicationService() throws Exception {
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/api/v1/merchants/m-1/payouts"))
                .willReturn(okJson("[]")));

        mockMvc.perform(get("/api/v1/merchants/m-1/payouts"))
                .andExpect(status().isOk());
    }

    @Test
    void routesRepaymentPlanPaths_toRepaymentReconciliationService() throws Exception {
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/api/v1/repayment-plans/plan-1"))
                .willReturn(okJson("{}")));

        mockMvc.perform(get("/api/v1/repayment-plans/plan-1"))
                .andExpect(status().isOk());
    }

    @Test
    void routesOpsPaths_toRepaymentReconciliationService() throws Exception {
        wireMock.stubFor(WireMock.post(urlPathEqualTo("/api/v1/ops/failed-events/e-1/retry"))
                .willReturn(okJson("{\"status\":\"RESOLVED\"}")));

        mockMvc.perform(post("/api/v1/ops/failed-events/e-1/retry"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"RESOLVED\"}"));
    }

    @Test
    void downstreamErrorStatusAndBody_passThroughUnchanged() throws Exception {
        wireMock.stubFor(WireMock.post(urlPathEqualTo("/api/v1/ops/failed-events/e-1/retry"))
                .willReturn(WireMock.status(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"CONFLICT\"}")));

        mockMvc.perform(post("/api/v1/ops/failed-events/e-1/retry"))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\":\"CONFLICT\"}"));
    }

    @Test
    void unmatchedPath_returnsApiErrorShaped404() throws Exception {
        mockMvc.perform(get("/api/v1/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void generatesAndForwardsACorrelationId_whenClientSendsNone() throws Exception {
        wireMock.stubFor(WireMock.post(urlEqualTo("/api/v1/applicants")).willReturn(okJson("{}")));

        String responseCorrelationId = mockMvc.perform(post("/api/v1/applicants")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("X-Correlation-Id");

        assertThat(responseCorrelationId).isNotBlank();
        wireMock.verify(postRequestedFor(urlEqualTo("/api/v1/applicants"))
                .withHeader("X-Correlation-Id", equalTo(responseCorrelationId)));
    }
}
