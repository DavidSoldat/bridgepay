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

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/v1/ops/applicants/** must win over the broader /api/v1/ops/** route. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class OpsRoutingIntegrationTest {

    static WireMockServer applicantService = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static WireMockServer repaymentService = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @BeforeAll
    static void start() {
        applicantService.start();
        repaymentService.start();
    }

    @AfterAll
    static void stop() {
        applicantService.stop();
        repaymentService.stop();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("bridgepay.applicant-service.base-url", applicantService::baseUrl);
        registry.add("bridgepay.application-service.base-url", repaymentService::baseUrl);
        registry.add("bridgepay.repayment-reconciliation-service.base-url", repaymentService::baseUrl);
    }

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void reset() {
        applicantService.resetAll();
        repaymentService.resetAll();
    }

    @Test
    void opsApplicantLookups_goToApplicantService() throws Exception {
        applicantService.stubFor(WireMock.get(urlEqualTo("/api/v1/ops/applicants/s-1"))
                .willReturn(okJson("{\"subject\":\"s-1\"}")));

        mockMvc.perform(get("/api/v1/ops/applicants/s-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("s-1"));
        repaymentService.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void otherOpsPaths_stillGoToRepaymentReconciliation() throws Exception {
        repaymentService.stubFor(WireMock.get(urlPathEqualTo("/api/v1/ops/failed-events"))
                .willReturn(okJson("{\"content\":[]}")));

        mockMvc.perform(get("/api/v1/ops/failed-events"))
                .andExpect(status().isOk());
        applicantService.verify(0, anyRequestedFor(anyUrl()));
    }
}
