package com.bridgepay.gateway.audit;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuditFilterIntegrationTest {

    static final String U = "0192a3b4-c5d6-7e8f-9a0b-1c2d3e4f5a6b";
    static final String M = "0192a3b4-0000-7000-8000-000000000001";
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
        r.add("bridgepay.application-service.base-url", wireMock::baseUrl);
        r.add("bridgepay.repayment-reconciliation-service.base-url", wireMock::baseUrl);
        r.add("bridgepay.notifications-service.base-url", wireMock::baseUrl);
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
    @MockitoSpyBean
    AuditEntryRepository repository;

    @BeforeEach
    void clean() {
        reset(repository);
        repository.deleteAll();
        wireMock.resetAll();
    }

    static RequestPostProcessor as(String username, String subject, String... roles) {
        return jwt().jwt(j -> j.subject(subject).claim("preferred_username", username))
                .authorities(Arrays.stream(roles).map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    private List<AuditEntry> entries() {
        return repository.findAll();
    }

    @Test
    void recordsAnOpsReadOfAShopper() throws Exception {
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/api/v1/ops/applicants/" + U)).willReturn(okJson("{}")));

        mockMvc.perform(get("/api/v1/ops/applicants/" + U).header("X-Correlation-Id", "corr-1")
                        .with(as("ops1", "ops-sub", "OPS")))
                .andExpect(status().isOk());

        assertThat(entries()).singleElement().satisfies(e -> {
            assertThat(e.getAction()).isEqualTo("VIEW_SHOPPER_PROFILE");
            assertThat(e.getActorUsername()).isEqualTo("ops1");
            assertThat(e.getActorSubject()).isEqualTo("ops-sub");
            assertThat(e.getActorRole()).isEqualTo("OPS");
            assertThat(e.getTargetType()).isEqualTo("SHOPPER");
            assertThat(e.getTargetId()).isEqualTo(U);
            assertThat(e.getStatus()).isEqualTo(200);
            assertThat(e.getHttpMethod()).isEqualTo("GET");
            assertThat(e.getPath()).isEqualTo("/api/v1/ops/applicants/" + U);
            assertThat(e.getCorrelationId()).isEqualTo("corr-1");
        });
    }

    @Test
    void doesNotRecordAShopperReadingTheirOwnApplication() throws Exception {
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/api/v1/applications/" + U)).willReturn(okJson("{}")));
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/api/v1/repayment-plans/" + U)).willReturn(okJson("{}")));

        mockMvc.perform(get("/api/v1/applications/" + U).with(as("shopper1", "shop-sub", "SHOPPER"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/repayment-plans/" + U).with(as("shopper1", "shop-sub", "SHOPPER"))).andExpect(status().isOk());

        assertThat(entries()).isEmpty();
    }

    @Test
    void recordsAShopperDeniedOnAnOpsRoute() throws Exception {
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/api/v1/applications/" + U + "/case")).willReturn(WireMock.status(403)));

        mockMvc.perform(get("/api/v1/applications/" + U + "/case").with(as("shopper1", "shop-sub", "SHOPPER")))
                .andExpect(status().isForbidden());

        assertThat(entries()).singleElement().satisfies(e -> {
            assertThat(e.getAction()).isEqualTo("VIEW_CASE_FILE");
            assertThat(e.getActorRole()).isEqualTo("OTHER");
            assertThat(e.getStatus()).isEqualTo(403);
        });
    }

    @Test
    void recordsMerchantRefundAndExportWithDetail() throws Exception {
        wireMock.stubFor(WireMock.post(urlPathEqualTo("/api/v1/merchants/" + M + "/orders/" + U + "/refund")).willReturn(WireMock.status(202)));
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/api/v1/merchants/" + M + "/sales/export")).willReturn(WireMock.ok("a,b\n")));

        mockMvc.perform(post("/api/v1/merchants/" + M + "/orders/" + U + "/refund").with(as("merchant1", "m-sub", "MERCHANT")))
                .andExpect(status().isAccepted());
        mockMvc.perform(get("/api/v1/merchants/" + M + "/sales/export").queryParam("status", "ALL").with(as("merchant1", "m-sub", "MERCHANT")))
                .andExpect(status().isOk());

        assertThat(entries()).extracting(AuditEntry::getAction, AuditEntry::getActorRole, AuditEntry::getTargetType, AuditEntry::getDetail)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("REFUND_ORDER", "MERCHANT", "APPLICATION", "merchantId=" + M),
                        Tuple.tuple("EXPORT_SALES", "MERCHANT", "MERCHANT", "ALL"));
    }

    @Test
    void doesNotRecordUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/api/v1/ops/applicants/" + U)).andExpect(status().isUnauthorized());
        assertThat(entries()).isEmpty();
    }

    @Test
    void auditWriteFailureStillReturnsTheDownstreamResponse() throws Exception {
        wireMock.stubFor(WireMock.post(urlPathEqualTo("/api/v1/applications/" + U + "/review-decision"))
                .willReturn(okJson("{\"status\":\"APPROVED\"}")));
        doThrow(new DataAccessResourceFailureException("db down")).when(repository).save(any());

        mockMvc.perform(post("/api/v1/applications/" + U + "/review-decision").with(as("ops1", "ops-sub", "OPS"))
                        .contentType("application/json").content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"APPROVED\"}"));
    }
}
