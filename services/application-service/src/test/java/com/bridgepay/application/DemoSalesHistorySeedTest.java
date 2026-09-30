package com.bridgepay.application;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The demo seed only runs where SPRING_FLYWAY_LOCATIONS adds classpath:db/demo; this runs Flyway the same way. */
@Testcontainers
class DemoSalesHistorySeedTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bridgepay").withUsername("test").withPassword("test");

    private static final String DEMO = "a.applicant_id::text LIKE '00000000-0000-7000-8000-0000000de0%'";

    @Test
    void seedsAPlausibleHistoryForTheDemoMerchantAndCanBeReRun() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("application").defaultSchema("application")
                .locations("classpath:db/migration", "classpath:db/demo")
                .load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        long total = count(jdbc, "SELECT count(*) FROM application.applications a WHERE " + DEMO);
        assertThat(total).isBetween(180L, 220L);
        assertThat(count(jdbc, "SELECT count(*) FROM application.applications a WHERE " + DEMO
                + " AND a.merchant_id <> '00000000-0000-7000-8000-000000000001'")).isZero();
        assertThat(count(jdbc, "SELECT count(*) FROM application.applications a WHERE " + DEMO
                + " AND a.status = 'MANUAL_REVIEW'")).isZero();
        assertThat(count(jdbc, "SELECT count(*) FROM application.applications a WHERE " + DEMO
                + " AND NOT a.is_demo")).isZero();
        long approved = count(jdbc, "SELECT count(*) FROM application.applications a WHERE " + DEMO
                + " AND a.status IN ('APPROVED', 'CANCELLED')");
        assertThat((double) approved / total).isBetween(0.65, 0.80);
        // manual-review history for the ops dashboard: decided by ops1/ops2, never still waiting
        long reviewed = count(jdbc, "SELECT count(*) FROM application.applications a WHERE " + DEMO
                + " AND a.decision_source = 'OPS'");
        assertThat((double) reviewed / total).isBetween(0.12, 0.24);
        assertThat(count(jdbc, "SELECT count(DISTINCT a.decided_by) FROM application.applications a WHERE " + DEMO
                + " AND a.decision_source = 'OPS'")).isEqualTo(2);
        assertThat(count(jdbc, "SELECT count(*) FROM application.applications a WHERE " + DEMO
                + " AND a.decision_source = 'OPS' AND (a.decided_by NOT IN ('ops1', 'ops2')"
                + " OR a.risk_score < 0.3 OR a.risk_score > 0.7 OR a.reviewer_note IS NULL"
                + " OR a.decision_at - a.created_at < interval '10 minutes'"
                + " OR a.decision_at - a.created_at > interval '20 hours' OR a.decision_at > now())")).isZero();
        assertThat(count(jdbc, "SELECT count(*) FROM application.applications a WHERE " + DEMO
                + " AND a.decision_source IS DISTINCT FROM 'OPS' AND a.decision_source IS DISTINCT FROM 'MODEL'")).isZero();
        // a payout never exists before the decision that created it
        assertThat(count(jdbc, "SELECT count(*) FROM application.merchant_payouts p JOIN application.applications a"
                + " ON a.id = p.application_id WHERE " + DEMO + " AND p.created_at < a.decision_at")).isZero();
        // every approved (incl. later cancelled) order has exactly one payout, declined ones none
        assertThat(count(jdbc, "SELECT count(*) FROM application.applications a LEFT JOIN application.merchant_payouts p"
                + " ON p.application_id = a.id WHERE " + DEMO
                + " AND ((a.status IN ('APPROVED', 'CANCELLED')) <> (p.id IS NOT NULL))")).isZero();
        assertThat(count(jdbc, "SELECT count(*) FROM application.merchant_payouts p JOIN application.applications a"
                + " ON a.id = p.application_id WHERE " + DEMO
                + " AND p.status = 'PENDING' AND a.created_at < now() - interval '2 days'")).isZero();
        assertThat(count(jdbc, "SELECT count(*) FROM application.applications a WHERE " + DEMO
                + " AND a.created_at > now()")).isZero();
        assertThat(count(jdbc, "SELECT count(*) FROM application.merchant_payouts p JOIN application.applications a"
                + " ON a.id = p.application_id WHERE " + DEMO + " AND p.paid_at > now()")).isZero();
        long last30 = count(jdbc, "SELECT count(*) FROM application.applications a WHERE " + DEMO
                + " AND a.created_at >= now() - interval '30 days'");
        long previous30 = count(jdbc, "SELECT count(*) FROM application.applications a WHERE " + DEMO
                + " AND a.created_at < now() - interval '30 days' AND a.created_at >= now() - interval '60 days'");
        assertThat(last30).isGreaterThan(previous30);

        List<Map<String, Object>> before = statusCounts(jdbc);
        String script = new String(getClass().getResourceAsStream("/db/demo/R__demo_sales_history.sql").readAllBytes(),
                StandardCharsets.UTF_8);
        jdbc.execute(script);
        assertThat(statusCounts(jdbc)).isEqualTo(before);
    }

    private static long count(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static List<Map<String, Object>> statusCounts(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT a.status, count(*) AS n FROM application.applications a WHERE " + DEMO
                + " GROUP BY a.status ORDER BY a.status");
    }
}
