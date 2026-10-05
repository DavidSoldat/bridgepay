package com.bridgepay.application.service;

import com.bridgepay.application.dto.OpsDashboardResponse;
import com.bridgepay.application.dto.OpsDashboardResponse.Reviewer;
import com.bridgepay.application.dto.OpsDashboardResponse.ScoreBin;
import com.bridgepay.application.dto.OpsDashboardResponse.SeriesPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class OpsDashboardServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bridgepay").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @TestConfiguration
    static class TestOverrides {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none").claim("sub", "unused")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        }
    }

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29); // a Tuesday
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final UUID MERCHANT = UUID.fromString("00000000-0000-7000-8000-000000000001"); // seeded by V2

    @Autowired private OpsDashboardService service;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // platform-wide numbers: start from an empty table every test
        jdbc.update("DELETE FROM application.merchant_payouts");
        jdbc.update("DELETE FROM application.applications");
        // current 7-day period: Sep 23..29, previous: Sep 16..22
        app("APPROVED", "MODEL", null, 0.12, "2026-09-29T10:00:00Z", 3L, false);
        app("CANCELLED", "MODEL", null, 0.20, "2026-09-24T10:00:00Z", 3L, false);      // auto-approved, not approved
        app("DECLINED", "MODEL", null, 0.85, "2026-09-25T10:00:00Z", 3L, false);
        app("APPROVED", "OPS", "ops1", 0.30, "2026-09-26T10:00:00Z", 3600L, false);
        app("DECLINED", "OPS", "ops1", 0.55, "2026-09-26T12:00:00Z", 10800L, false);
        app("APPROVED", "OPS", "ops2", 0.69, "2026-09-27T09:00:00Z", 7200L, false);
        app("MANUAL_REVIEW", null, null, 0.45, "2026-09-28T09:00:00Z", null, false);
        app("APPROVED", null, null, null, "2026-09-23T09:00:00Z", 3L, false);          // decided before V5
        app("APPROVED", "MODEL", null, 1.0, "2026-09-29T08:00:00Z", 3L, true);         // demo
        app("MANUAL_REVIEW", null, null, null, "2026-09-29T09:00:00Z", null, true);    // demo, never in the queue
        // previous period
        app("APPROVED", "MODEL", null, 0.10, "2026-09-20T10:00:00Z", 3L, false);
        app("DECLINED", "OPS", "ops1", 0.50, "2026-09-18T10:00:00Z", 36000L, false);
        // before both periods, still waiting
        app("MANUAL_REVIEW", null, null, 0.40, "2026-09-10T08:00:00Z", null, false);
    }

    private void app(String status, String source, String decidedBy, Double risk, String createdAt,
                     Long decidedAfterSeconds, boolean demo) {
        OffsetDateTime created = OffsetDateTime.parse(createdAt);
        OffsetDateTime decided = decidedAfterSeconds == null ? null : created.plusSeconds(decidedAfterSeconds);
        jdbc.update("""
                INSERT INTO application.applications (id, applicant_id, merchant_id, amount, status, risk_score,
                    decision_source, decided_by, decision_at, is_demo, created_at, updated_at)
                VALUES (?, ?, ?, 10.00, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), UUID.randomUUID(), MERCHANT, status, risk, source, decidedBy, decided, demo,
                created, created);
    }

    @Test
    void totalsSplitRoutingAndOutcomes() {
        OpsDashboardResponse d = service.dashboard(7, UTC, TODAY);

        assertThat(d.from()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(d.bucket()).isEqualTo("DAY");
        var c = d.current();
        assertThat(c.applications()).isEqualTo(10);
        assertThat(c.autoDecided()).isEqualTo(4);
        assertThat(c.reviewed()).isEqualTo(3);
        assertThat(c.waiting()).isEqualTo(2);
        assertThat(c.approved()).isEqualTo(5);
        assertThat(c.declined()).isEqualTo(2);
        assertThat(c.approvalRate()).isEqualTo(5.0 / 7);
        assertThat(c.medianReviewSeconds()).isEqualTo(7200L);

        var p = d.previous();
        assertThat(p.applications()).isEqualTo(2);
        assertThat(p.autoDecided()).isEqualTo(1);
        assertThat(p.reviewed()).isEqualTo(1);
        assertThat(p.approvalRate()).isEqualTo(0.5);
        assertThat(p.medianReviewSeconds()).isEqualTo(36000L);
    }

    @Test
    void queueSnapshotCountsOnlyRealWaitingApplications() {
        var q = service.dashboard(7, UTC, TODAY).queue();

        assertThat(q.inReview()).isEqualTo(2);
        assertThat(q.oldestSubmittedAt()).isEqualTo(OffsetDateTime.of(2026, 9, 10, 8, 0, 0, 0, ZoneOffset.UTC).toInstant());
    }

    @Test
    void emptyPeriodHasNullRatesAndAnEmptyQueue() {
        jdbc.update("DELETE FROM application.applications");

        OpsDashboardResponse d = service.dashboard(7, UTC, TODAY);

        assertThat(d.current().applications()).isZero();
        assertThat(d.current().approvalRate()).isNull();
        assertThat(d.current().medianReviewSeconds()).isNull();
        assertThat(d.queue().inReview()).isZero();
        assertThat(d.queue().oldestSubmittedAt()).isNull();
        assertThat(d.scoreHistogram()).hasSize(10).allMatch(b -> b.count() == 0);
        assertThat(d.reviewers()).isEmpty();
    }

    @Test
    void dailySeriesSplitsAutoApprovedAutoDeclinedAndReview() {
        var series = service.dashboard(7, UTC, TODAY).series();

        assertThat(series).extracting(SeriesPoint::start).startsWith(LocalDate.of(2026, 9, 23)).hasSize(7);
        assertThat(series).extracting(SeriesPoint::autoApproved).containsExactly(0L, 1L, 0L, 0L, 0L, 0L, 2L);
        assertThat(series).extracting(SeriesPoint::autoDeclined).containsExactly(0L, 0L, 1L, 0L, 0L, 0L, 0L);
        assertThat(series).extracting(SeriesPoint::review).containsExactly(0L, 0L, 0L, 2L, 1L, 1L, 1L);
    }

    @Test
    void ninetyDaysAreWeekly() {
        OpsDashboardResponse d = service.dashboard(90, UTC, TODAY);

        assertThat(d.bucket()).isEqualTo("WEEK");
        assertThat(d.series()).hasSize(14);
        assertThat(d.series().get(0).start()).isEqualTo(d.from());
    }

    @Test
    void histogramHasTenBinsWithEdgesInTheUpperBin() {
        var bins = service.dashboard(7, UTC, TODAY).scoreHistogram();

        assertThat(bins).hasSize(10);
        assertThat(bins.get(3).from()).isEqualTo(0.3);
        assertThat(bins.get(3).to()).isEqualTo(0.4);
        assertThat(bins).extracting(ScoreBin::count).containsExactly(0L, 1L, 1L, 1L, 1L, 1L, 1L, 0L, 1L, 1L);
    }

    @Test
    void reviewersAreRankedByDecisionsWithTheirOwnMedian() {
        var reviewers = service.dashboard(7, UTC, TODAY).reviewers();

        assertThat(reviewers).extracting(Reviewer::name).containsExactly("ops1", "ops2");
        Reviewer ops1 = reviewers.get(0);
        assertThat(ops1.decisions()).isEqualTo(2);
        assertThat(ops1.approved()).isEqualTo(1);
        assertThat(ops1.declined()).isEqualTo(1);
        assertThat(ops1.medianReviewSeconds()).isEqualTo(7200L);
    }

    @Test
    void anOpsDecisionWithoutAReviewerNameIsUnknown() {
        app("APPROVED", "OPS", null, 0.4, "2026-09-28T10:00:00Z", 600L, false);

        assertThat(service.dashboard(7, UTC, TODAY).reviewers()).extracting(Reviewer::name).contains("Unknown");
    }

    @Test
    void refundedOrdersStillCountAsApprovedDecisions() {
        jdbc.update("DELETE FROM application.applications");
        app("APPROVED", "MODEL", null, 0.10, "2026-09-27T10:00:00Z", 3L, false);
        app("REFUND_PENDING", "MODEL", null, 0.10, "2026-09-27T11:00:00Z", 3L, false);
        app("REFUNDED", "MODEL", null, 0.10, "2026-09-27T12:00:00Z", 3L, false);
        app("DECLINED", "MODEL", null, 0.90, "2026-09-27T13:00:00Z", 3L, false);

        var c = service.dashboard(7, UTC, TODAY).current();

        assertThat(c.approved()).isEqualTo(3);
        assertThat(c.approvalRate()).isEqualTo(0.75);
    }
}
