package com.bridgepay.notifications.web;

import com.bridgepay.notifications.AbstractKafkaIntegrationTest;
import com.github.f4b6a3.uuid.UuidCreator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("local")
@AutoConfigureMockMvc
class LocalProfileFeedIntegrationTest extends AbstractKafkaIntegrationTest {

    private static final String DEMO_SUBJECT = "00000000-0000-7000-8000-000000000099";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private void insert(UUID applicant, String title) {
        jdbc.update("""
                insert into notifications.notification_log (id, event_id, applicant_id, type, title, body, sent_at)
                values (?, ?, ?, 'PLAN_COMPLETED', ?, '', ?)""",
                UuidCreator.getTimeOrderedEpoch(), UUID.randomUUID(), applicant, title, OffsetDateTime.now());
    }

    private static String unsignedToken(String subject) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(("{\"sub\":\"" + subject + "\"}").getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Test
    void usesTheSubjectFromARealBearerToken() throws Exception {
        UUID shopper = UUID.randomUUID();
        insert(shopper, "Mine");

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + unsignedToken(shopper.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Mine"));
    }

    @Test
    void fallsBackToTheDemoSubjectWithoutAToken() throws Exception {
        insert(UUID.fromString(DEMO_SUBJECT), "Demo shopper's");

        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Demo shopper's"));
    }
}
