package com.bridgepay.notifications.web;

import com.bridgepay.notifications.AbstractKafkaIntegrationTest;
import com.github.f4b6a3.uuid.UuidCreator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class NotificationFeedIntegrationTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private final Instant now = Instant.now();

    private void insert(UUID applicant, String type, String title, String body, UUID applicationId,
                        String groupKey, Integer seq, String amount, Instant sentAt) {
        jdbc.update("""
                insert into notifications.notification_log
                  (id, event_id, applicant_id, type, title, body, application_id, group_key, sequence_number, amount, sent_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UuidCreator.getTimeOrderedEpoch(), UUID.randomUUID(), applicant, type, title, body, applicationId,
                groupKey, seq, amount == null ? null : new BigDecimal(amount), OffsetDateTime.ofInstant(sentAt, ZoneOffset.UTC));
    }

    private Instant ago(int minutes) {
        return now.minus(Duration.ofMinutes(minutes));
    }

    private ResultActions feed(UUID applicant, String query) throws Exception {
        return mockMvc.perform(get("/api/v1/notifications" + query).with(jwt().jwt(j -> j.subject(applicant.toString()))));
    }

    /** A checkout, its first payment, then a payoff of installments 2–4 in one Paddle transaction. */
    private UUID payoffHistory() {
        UUID applicant = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        insert(applicant, "APPLICATION_APPROVED", "You're approved", "4 payments of $17.44 for your $69.76 order.", order, null, null, null, ago(30));
        insert(applicant, "INSTALLMENT_PAID", "Payment 1 received", "$17.44", order, "txn_first", 1, "17.44", ago(29));
        insert(applicant, "INSTALLMENT_PAID", "Payment 2 received", "$17.44", order, "txn_payoff", 2, "17.44", ago(5));
        insert(applicant, "INSTALLMENT_PAID", "Payment 3 received", "$17.44", order, "txn_payoff", 3, "17.44", ago(5));
        insert(applicant, "INSTALLMENT_PAID", "Payment 4 received", "$17.44", order, "txn_payoff", 4, "17.44", ago(5));
        insert(applicant, "PLAN_COMPLETED", "Plan paid off", "Thanks — you've paid everything for this order.", order, null, null, null, ago(4));
        return applicant;
    }

    @Test
    void feed_foldsAPayoffIntoOneEntry_newestFirst() throws Exception {
        UUID applicant = payoffHistory();

        feed(applicant, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].title").value("Plan paid off"))
                .andExpect(jsonPath("$.items[1].title").value("Payments 2–4 received"))
                .andExpect(jsonPath("$.items[1].body").value("$52.32"))
                .andExpect(jsonPath("$.items[1].type").value("INSTALLMENT_PAID"))
                .andExpect(jsonPath("$.items[2].title").value("Payment 1 received"))
                .andExpect(jsonPath("$.items[3].title").value("You're approved"))
                .andExpect(jsonPath("$.items[3].applicationId").isNotEmpty())
                .andExpect(jsonPath("$.unreadCount").value(4))
                .andExpect(jsonPath("$.items[0].unread").value(true))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void feed_pagesOverGroups_neverSplittingOne() throws Exception {
        UUID applicant = payoffHistory();

        feed(applicant, "?page=0&size=2")
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].title").value("Payments 2–4 received"))
                .andExpect(jsonPath("$.hasMore").value(true));
        feed(applicant, "?page=1&size=2")
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].title").value("Payment 1 received"))
                .andExpect(jsonPath("$.items[1].title").value("You're approved"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void unread_isOnlyWhatArrivedAfterTheRead() throws Exception {
        UUID applicant = payoffHistory();

        mockMvc.perform(post("/api/v1/notifications/read").with(jwt().jwt(j -> j.subject(applicant.toString()))))
                .andExpect(status().isNoContent());
        feed(applicant, "")
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.items[0].unread").value(false));

        insert(applicant, "INSTALLMENT_MISSED", "Payment 3 missed", "We'll retry your card.", null, null, null, null,
                Instant.now().plus(Duration.ofMinutes(1)));
        feed(applicant, "")
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Payment 3 missed"))
                .andExpect(jsonPath("$.items[0].unread").value(true))
                .andExpect(jsonPath("$.items[1].unread").value(false));
    }

    @Test
    void read_neverMovesBackwards() throws Exception {
        UUID applicant = UUID.randomUUID();
        // Postgres rounds to microseconds on insert (it doesn't truncate), so start from a whole microsecond
        Instant later = now.plus(Duration.ofHours(1)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("insert into notifications.notification_reads (applicant_id, read_through) values (?, ?)",
                applicant, OffsetDateTime.ofInstant(later, ZoneOffset.UTC));

        mockMvc.perform(post("/api/v1/notifications/read").with(jwt().jwt(j -> j.subject(applicant.toString()))))
                .andExpect(status().isNoContent());

        OffsetDateTime stored = jdbc.queryForObject(
                "select read_through from notifications.notification_reads where applicant_id = ?", OffsetDateTime.class, applicant);
        assertThat(stored.toInstant()).isEqualTo(later);
    }

    @Test
    void feed_neverShowsAnotherShoppersNotifications() throws Exception {
        UUID someoneElse = payoffHistory();
        UUID me = UUID.randomUUID();

        feed(me, "")
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.unreadCount").value(0));
        assertThat(someoneElse).isNotEqualTo(me);
    }

    @Test
    void feed_listsRowsRecordedBeforeTheFeedExisted() throws Exception {
        UUID applicant = UUID.randomUUID();
        // what V2's backfill leaves on a pre-feed row: generic title, empty body, no link or group
        insert(applicant, "INSTALLMENT_PAID", "Payment received", "", null, null, null, null, ago(60));

        feed(applicant, "")
                .andExpect(jsonPath("$.items[0].title").value("Payment received"))
                .andExpect(jsonPath("$.items[0].body").value(""))
                .andExpect(jsonPath("$.items[0].applicationId").doesNotExist());
    }

    @Test
    void feed_rejectsBadPaging() throws Exception {
        UUID applicant = UUID.randomUUID();
        for (String query : new String[] {"?size=0", "?size=51", "?page=-1", "?page=abc"}) {
            feed(applicant, query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void feed_requiresAToken() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
    }
}
