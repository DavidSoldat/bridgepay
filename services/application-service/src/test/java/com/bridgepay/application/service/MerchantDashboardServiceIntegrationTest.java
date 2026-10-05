package com.bridgepay.application.service;

import com.bridgepay.application.domain.Merchant;
import com.bridgepay.application.dto.MerchantDashboardResponse;
import com.bridgepay.application.dto.MerchantDashboardResponse.SeriesPoint;
import com.bridgepay.application.repository.MerchantRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class MerchantDashboardServiceIntegrationTest {

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

    @Autowired private MerchantDashboardService service;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID merchant;

    @BeforeEach
    void seed() {
        merchant = merchantRepository.save(new Merchant("Dashboard merchant", new BigDecimal("2.90"))).getId();
        // current 7-day period: Sep 23..29, previous: Sep 16..22
        UUID paidNow = app(merchant, "APPROVED", "100.00", "2026-09-29T10:00:00Z");
        payout(paidNow, "100.00", "2.90", "PAID", "2026-09-29T12:00:00Z");
        UUID pending = app(merchant, "APPROVED", "50.00", "2026-09-23T00:30:00Z");
        payout(pending, "50.00", "1.45", "PENDING", null);
        app(merchant, "DECLINED", "30.00", "2026-09-25T09:00:00Z");
        app(merchant, "MANUAL_REVIEW", "40.00", "2026-09-26T09:00:00Z");
        UUID cancelled = app(merchant, "CANCELLED", "60.00", "2026-09-24T09:00:00Z");
        payout(cancelled, "60.00", "1.74", "CANCELLED", null);
        UUID previous = app(merchant, "APPROVED", "80.00", "2026-09-20T09:00:00Z");
        payout(previous, "80.00", "2.32", "PAID", "2026-09-21T09:00:00Z");
        // ordered in the previous period, paid in the current one
        UUID paidLater = app(merchant, "APPROVED", "70.00", "2026-09-22T09:00:00Z");
        payout(paidLater, "70.00", "2.03", "PAID", "2026-09-23T09:00:00Z");
        app(merchant, "APPROVED", "999.00", "2026-09-15T09:00:00Z"); // before both periods
        UUID other = merchantRepository.save(new Merchant("Other", new BigDecimal("2.90"))).getId();
        app(other, "APPROVED", "500.00", "2026-09-29T09:00:00Z");
    }

    private UUID app(UUID merchantId, String status, String amount, String createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO application.applications (id, applicant_id, merchant_id, amount, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)""",
                id, UUID.randomUUID(), merchantId, new BigDecimal(amount), status, createdAt, createdAt);
        return id;
    }

    private void payout(UUID applicationId, String amount, String fee, String status, String paidAt) {
        jdbc.update("""
                INSERT INTO application.merchant_payouts (id, application_id, merchant_id, amount, fee_amount, status, paid_at, created_at)
                SELECT ?, a.id, a.merchant_id, ?, ?, ?, ?::timestamptz, a.created_at
                FROM application.applications a WHERE a.id = ?""",
                UUID.randomUUID(), new BigDecimal(amount), new BigDecimal(fee), status, paidAt, applicationId);
    }

    @Test
    void totalsCountApplicationsByOrderDateAndPayoutsByPaidDate() {
        MerchantDashboardResponse d = service.dashboard(merchant, 7, UTC, TODAY);

        assertThat(d.from()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(d.to()).isEqualTo(TODAY);
        assertThat(d.bucket()).isEqualTo("DAY");
        assertThat(d.current().checkouts()).isEqualTo(5);
        assertThat(d.current().approved()).isEqualTo(2);
        assertThat(d.current().declined()).isEqualTo(1);
        assertThat(d.current().inReview()).isEqualTo(1);
        assertThat(d.current().paid()).isEqualTo(1);
        assertThat(d.current().approvedVolume()).isEqualByComparingTo("150.00");
        assertThat(d.current().approvalRate()).isEqualTo(2.0 / 3);
        assertThat(d.current().feesPaid()).isEqualByComparingTo("4.93");
        assertThat(d.current().netPaidOut()).isEqualByComparingTo("165.07");

        assertThat(d.previous().checkouts()).isEqualTo(2);
        assertThat(d.previous().approved()).isEqualTo(2);
        assertThat(d.previous().paid()).isEqualTo(2);
        assertThat(d.previous().approvedVolume()).isEqualByComparingTo("150.00");
        assertThat(d.previous().feesPaid()).isEqualByComparingTo("2.32");
        assertThat(d.previous().netPaidOut()).isEqualByComparingTo("77.68");

        assertThat(d.pendingPayout()).isEqualByComparingTo("48.55");
    }

    @Test
    void approvalRateIsNullAndMoneyIsZeroWhenNothingIsDecided() {
        UUID quiet = merchantRepository.save(new Merchant("Quiet", new BigDecimal("2.90"))).getId();
        app(quiet, "MANUAL_REVIEW", "40.00", "2026-09-28T09:00:00Z");

        MerchantDashboardResponse d = service.dashboard(quiet, 7, UTC, TODAY);

        assertThat(d.current().approvalRate()).isNull();
        assertThat(d.current().approvedVolume()).isEqualByComparingTo("0");
        assertThat(d.current().feesPaid()).isEqualByComparingTo("0");
        assertThat(d.pendingPayout()).isEqualByComparingTo("0");
    }

    @Test
    void dailySeriesHasEveryDayOfThePeriodIncludingEmptyOnes() {
        var series = service.dashboard(merchant, 7, UTC, TODAY).series();

        assertThat(series).extracting(SeriesPoint::start).containsExactly(
                LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 28), TODAY);
        assertThat(series).extracting(SeriesPoint::checkouts).containsExactly(1L, 1L, 1L, 1L, 0L, 0L, 1L);
        assertThat(series.get(0).approvedVolume()).isEqualByComparingTo("50.00");
        assertThat(series.get(1).approvedVolume()).isEqualByComparingTo("0"); // cancelled
        assertThat(series.get(6).approvedVolume()).isEqualByComparingTo("100.00");
        assertThat(service.dashboard(merchant, 30, UTC, TODAY).series()).hasSize(30);
    }

    @Test
    void ninetyDaysAreBucketedByMondayWeeksClampedToThePeriodStart() {
        MerchantDashboardResponse d = service.dashboard(merchant, 90, UTC, TODAY);

        assertThat(d.bucket()).isEqualTo("WEEK");
        assertThat(d.from()).isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(d.series()).hasSize(14);
        assertThat(d.series().get(0).start()).isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(d.series().get(1).start()).isEqualTo(LocalDate.of(2026, 7, 6));
        SeriesPoint lastWeek = d.series().get(13);
        assertThat(lastWeek.start()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(lastWeek.checkouts()).isEqualTo(1);
        SeriesPoint weekOfSep21 = d.series().get(12);
        assertThat(weekOfSep21.checkouts()).isEqualTo(5);
        assertThat(weekOfSep21.approvedVolume()).isEqualByComparingTo("120.00");
    }

    @Test
    void daysFollowTheRequestedTimeZone() {
        UUID late = merchantRepository.save(new Merchant("Late", new BigDecimal("2.90"))).getId();
        app(late, "APPROVED", "10.00", "2026-09-28T23:30:00Z"); // 01:30 on Sep 29 in Belgrade (UTC+2)

        var utc = service.dashboard(late, 7, UTC, TODAY).series();
        var belgrade = service.dashboard(late, 7, ZoneId.of("Europe/Belgrade"), TODAY).series();

        assertThat(utc.get(5).checkouts()).isEqualTo(1);      // Sep 28
        assertThat(belgrade.get(5).checkouts()).isEqualTo(0);
        assertThat(belgrade.get(6).checkouts()).isEqualTo(1); // Sep 29
    }

    @Test
    void rejectsPeriodsOtherThan7_30_90() {
        assertThatThrownBy(() -> service.dashboard(merchant, 14, UTC, TODAY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("days");
    }

    @Test
    void aPendingRefundStillCountsAsApproved_aFinishedRefundOnlyAsACheckout() {
        UUID refunds = merchantRepository.save(new Merchant("Refunds merchant", new BigDecimal("2.90"))).getId();
        UUID kept = app(refunds, "APPROVED", "100.00", "2026-09-27T10:00:00Z");
        payout(kept, "100.00", "2.90", "PAID", "2026-09-27T12:00:00Z");
        UUID pendingRefund = app(refunds, "REFUND_PENDING", "50.00", "2026-09-27T11:00:00Z");
        payout(pendingRefund, "50.00", "1.45", "PAID", "2026-09-27T12:00:00Z");
        UUID refunded = app(refunds, "REFUNDED", "40.00", "2026-09-27T12:00:00Z");
        payout(refunded, "40.00", "1.16", "REFUNDED", "2026-09-27T13:00:00Z");

        var totals = service.dashboard(refunds, 7, UTC, TODAY).current();

        assertThat(totals.checkouts()).isEqualTo(3);
        assertThat(totals.approved()).isEqualTo(2);
        assertThat(totals.approvedVolume()).isEqualByComparingTo("150.00");
        assertThat(totals.feesPaid()).isEqualByComparingTo("4.35");
        assertThat(totals.netPaidOut()).isEqualByComparingTo("145.65");
    }
}
