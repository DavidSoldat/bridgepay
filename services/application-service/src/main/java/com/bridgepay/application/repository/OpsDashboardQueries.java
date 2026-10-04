package com.bridgepay.application.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Aggregates for the ops dashboard, across all merchants. Dates are local dates in {@code tz}, inclusive.
 * Routing comes from decision_source: MODEL = decided automatically, OPS or still MANUAL_REVIEW = sent to review.
 */
@Repository
public class OpsDashboardQueries {

    public record TotalsRow(long applications, long autoApproved, long autoDeclined, long reviewed, long waiting,
                            long approved, long declined, Long medianReviewSeconds) {
    }

    public record QueueRow(long inReview, Instant oldest) {
    }

    public record BucketRow(LocalDate start, long autoApproved, long autoDeclined, long review) {
    }

    public record BinRow(int bin, long count) {
    }

    public record ReviewerRow(String name, long decisions, long approved, long declined, Long medianReviewSeconds) {
    }

    private static final String IN_PERIOD = "(a.created_at AT TIME ZONE :tz)::date BETWEEN :from AND :to";
    private static final String APPROVED = "a.status IN ('APPROVED', 'COMPLETED', 'DEFAULTED', 'REFUND_PENDING', 'REFUNDED')";
    /** %s is an optional FILTER clause: it must sit on the aggregate itself, inside round(). */
    private static final String MEDIAN_REVIEW =
            "round(percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM a.decision_at - a.created_at)) %s)";

    private final NamedParameterJdbcTemplate jdbc;

    public OpsDashboardQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TotalsRow totals(String tz, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                        SELECT count(*) AS applications,
                               count(*) FILTER (WHERE a.decision_source = 'MODEL' AND a.status <> 'DECLINED') AS auto_approved,
                               count(*) FILTER (WHERE a.decision_source = 'MODEL' AND a.status = 'DECLINED') AS auto_declined,
                               count(*) FILTER (WHERE a.decision_source = 'OPS') AS reviewed,
                               count(*) FILTER (WHERE a.status = 'MANUAL_REVIEW') AS waiting,
                               count(*) FILTER (WHERE %s) AS approved,
                               count(*) FILTER (WHERE a.status = 'DECLINED') AS declined,
                               %s AS median_review
                        FROM application.applications a
                        WHERE %s""".formatted(APPROVED,
                        MEDIAN_REVIEW.formatted("FILTER (WHERE a.decision_source = 'OPS')"), IN_PERIOD),
                params(tz, from, to),
                (rs, i) -> new TotalsRow(rs.getLong("applications"), rs.getLong("auto_approved"),
                        rs.getLong("auto_declined"), rs.getLong("reviewed"), rs.getLong("waiting"),
                        rs.getLong("approved"), rs.getLong("declined"), nullableLong(rs, "median_review")));
    }

    /** Snapshot: real applications waiting for review now. Demo rows never reach the queue, so not here either. */
    public QueueRow queue() {
        return jdbc.queryForObject("""
                        SELECT count(*) AS n, min(a.created_at) AS oldest
                        FROM application.applications a
                        WHERE a.status = 'MANUAL_REVIEW' AND NOT a.is_demo""",
                new MapSqlParameterSource(),
                (rs, i) -> {
                    OffsetDateTime oldest = rs.getObject("oldest", OffsetDateTime.class);
                    return new QueueRow(rs.getLong("n"), oldest == null ? null : oldest.toInstant());
                });
    }

    /** Non-empty buckets only; unit is 'day' or 'week' (ISO, Monday). The service fills the gaps. */
    public List<BucketRow> buckets(String tz, LocalDate from, LocalDate to, String unit) {
        return jdbc.query("""
                        SELECT date_trunc(:unit, a.created_at AT TIME ZONE :tz)::date AS start,
                               count(*) FILTER (WHERE a.decision_source = 'MODEL' AND a.status <> 'DECLINED') AS auto_approved,
                               count(*) FILTER (WHERE a.decision_source = 'MODEL' AND a.status = 'DECLINED') AS auto_declined,
                               count(*) FILTER (WHERE a.decision_source = 'OPS' OR a.status = 'MANUAL_REVIEW') AS review
                        FROM application.applications a
                        WHERE %s
                        GROUP BY 1""".formatted(IN_PERIOD),
                params(tz, from, to).addValue("unit", unit),
                (rs, i) -> new BucketRow(rs.getObject("start", LocalDate.class), rs.getLong("auto_approved"),
                        rs.getLong("auto_declined"), rs.getLong("review")));
    }

    /** Non-empty bins only, 0..9; a score of 1.0 falls in bin 9. */
    public List<BinRow> scoreBins(String tz, LocalDate from, LocalDate to) {
        return jdbc.query("""
                        SELECT least(floor(a.risk_score * 10), 9)::int AS bin, count(*) AS n
                        FROM application.applications a
                        WHERE a.risk_score IS NOT NULL AND %s
                        GROUP BY 1""".formatted(IN_PERIOD),
                params(tz, from, to),
                (rs, i) -> new BinRow(rs.getInt("bin"), rs.getLong("n")));
    }

    public List<ReviewerRow> reviewers(String tz, LocalDate from, LocalDate to) {
        return jdbc.query("""
                        SELECT coalesce(a.decided_by, 'Unknown') AS name, count(*) AS decisions,
                               count(*) FILTER (WHERE %s) AS approved,
                               count(*) FILTER (WHERE a.status = 'DECLINED') AS declined,
                               %s AS median_review
                        FROM application.applications a
                        WHERE a.decision_source = 'OPS' AND %s
                        GROUP BY 1
                        ORDER BY decisions DESC, name ASC""".formatted(APPROVED, MEDIAN_REVIEW.formatted(""), IN_PERIOD),
                params(tz, from, to),
                (rs, i) -> new ReviewerRow(rs.getString("name"), rs.getLong("decisions"), rs.getLong("approved"),
                        rs.getLong("declined"), nullableLong(rs, "median_review")));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static MapSqlParameterSource params(String tz, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource().addValue("tz", tz).addValue("from", from).addValue("to", to);
    }
}
