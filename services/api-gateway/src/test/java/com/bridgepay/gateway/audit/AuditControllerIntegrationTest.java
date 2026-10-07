package com.bridgepay.gateway.audit;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuditControllerIntegrationTest {

    static final String S1 = "0192a3b4-c5d6-7e8f-9a0b-1c2d3e4f5a6b";
    static final String S2 = "0192a3b4-c5d6-7e8f-9a0b-1c2d3e4f5a6c";

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
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AuditEntryRepository repository;

    static RequestPostProcessor as(String username, String... roles) {
        return jwt().jwt(j -> j.subject(username + "-sub").claim("preferred_username", username))
                .authorities(Arrays.stream(roles).map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    private void insert(String at, String actor, String role, String action, String type, String target, int status) {
        jdbc.update("""
                INSERT INTO audit.audit_entries (id, occurred_at, actor_subject, actor_username, actor_role, action,
                  target_type, target_id, http_method, path, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'GET', '/x', ?)""",
                UUID.randomUUID(), Timestamp.from(Instant.parse(at)), actor + "-sub", actor, role, action, type, target, status);
    }

    @BeforeEach
    void seed() {
        repository.deleteAll();
        insert("2026-10-01T09:00:00Z", "ops1", "OPS", "VIEW_SHOPPER_PROFILE", "SHOPPER", S1, 200);
        insert("2026-10-02T21:30:00Z", "ops2", "OPS", "VIEW_CASE_FILE", "APPLICATION", S2, 200);
        insert("2026-10-03T10:00:00Z", "shopper1", "OTHER", "VIEW_CASE_FILE", "APPLICATION", S2, 403);
        insert("2026-10-04T10:00:00Z", "merchant1", "MERCHANT", "REFUND_ORDER", "APPLICATION", S2, 409);
    }

    @Test
    void listsNewestFirstWithoutTheActorSubject() throws Exception {
        mockMvc.perform(get("/api/v1/audit").with(as("ops1", "OPS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].action").value("REFUND_ORDER"))
                .andExpect(jsonPath("$.content[3].action").value("VIEW_SHOPPER_PROFILE"))
                .andExpect(jsonPath("$.content[0].actorSubject").doesNotExist());
    }

    @Test
    void filtersCombineWithAnd() throws Exception {
        mockMvc.perform(get("/api/v1/audit").queryParam("actor", "ops2").with(as("ops1", "OPS")))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/audit").queryParam("action", "VIEW_CASE_FILE").with(as("ops1", "OPS")))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/audit").queryParam("targetType", "APPLICATION").queryParam("targetId", S2)
                        .queryParam("outcome", "DENIED").with(as("ops1", "OPS")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actorUsername").value("shopper1"));
        mockMvc.perform(get("/api/v1/audit").queryParam("outcome", "FAILED").with(as("ops1", "OPS")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value(409));
        // Narrowed by action: this test's own earlier reads of the log are recorded as ALLOWED too.
        mockMvc.perform(get("/api/v1/audit").queryParam("outcome", "ALLOWED").queryParam("action", "VIEW_CASE_FILE")
                        .with(as("ops1", "OPS")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actorUsername").value("ops2"));
    }

    @Test
    void dateRangeIsInclusiveCalendarDaysInTheCallersZone() throws Exception {
        // 2026-10-02T21:30Z is 2026-10-02 23:30 in Europe/Belgrade (UTC+2) - inside to=2026-10-02.
        mockMvc.perform(get("/api/v1/audit").queryParam("from", "2026-10-02").queryParam("to", "2026-10-02")
                        .queryParam("tz", "Europe/Belgrade").with(as("ops1", "OPS")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actorUsername").value("ops2"));
        // In Asia/Tokyo (UTC+9) the same instant is already 2026-10-03.
        mockMvc.perform(get("/api/v1/audit").queryParam("from", "2026-10-02").queryParam("to", "2026-10-02")
                        .queryParam("tz", "Asia/Tokyo").with(as("ops1", "OPS")))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void badParamsAre400() throws Exception {
        String[][] bad = {
                {"action", "NOPE"}, {"outcome", "MAYBE"}, {"targetType", "SHOPPER"}, {"targetId", S1},
                {"from", "yesterday"}, {"tz", "+02:00"}, {"page", "-1"}, {"page", "abc"},
        };
        for (String[] p : bad) {
            mockMvc.perform(get("/api/v1/audit").queryParam(p[0], p[1]).with(as("ops1", "OPS")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }
        mockMvc.perform(get("/api/v1/audit").queryParam("from", "2026-10-05").queryParam("to", "2026-10-01").with(as("ops1", "OPS")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void merchantsAndShoppersGet403AndTheDenialIsRecorded() throws Exception {
        mockMvc.perform(get("/api/v1/audit").with(as("merchant1", "MERCHANT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/audit").with(as("shopper1"))).andExpect(status().isForbidden());

        assertThat(repository.findAll()).filteredOn(e -> e.getAction().equals("VIEW_AUDIT_LOG"))
                .extracting(AuditEntry::getActorUsername, AuditEntry::getStatus)
                .containsExactlyInAnyOrder(Tuple.tuple("merchant1", 403), Tuple.tuple("shopper1", 403));
    }

    @Test
    void readingTheLogIsRecordedWithItsFilters() throws Exception {
        mockMvc.perform(get("/api/v1/audit").queryParam("actor", "ops2").with(as("ops1", "OPS"))).andExpect(status().isOk());

        assertThat(repository.findAll()).filteredOn(e -> e.getAction().equals("VIEW_AUDIT_LOG"))
                .singleElement().satisfies(e -> {
                    assertThat(e.getActorUsername()).isEqualTo("ops1");
                    assertThat(e.getDetail()).isEqualTo("actor=ops2");
                });
    }
}
