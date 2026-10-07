package com.bridgepay.application.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Model monitoring aggregates. Drift is over applications created in the period, performance over applications
 * decided in it. Dates are local dates in {@code tz}, inclusive. Bins: least(floor(score*10), 9).
 */
@Repository
public class ModelMonitoringQueries {

    public record BinRow(int bin, long count) {
    }

    public record FactorRow(String feature, long count, double mean) {
    }

    public record OutcomeRow(int bin, long finished, long defaulted) {
    }

    public record ReviewRow(long decided, long agreed) {
    }

    public record SourceOutcomeRow(String source, long finished, long defaulted) {
    }

    private static final String CREATED = "(a.created_at AT TIME ZONE :tz)::date BETWEEN :from AND :to";
    private static final String DECIDED = "(a.decision_at AT TIME ZONE :tz)::date BETWEEN :from AND :to";
    private static final String APPROVED = "a.status IN ('APPROVED', 'COMPLETED', 'DEFAULTED', 'REFUND_PENDING', 'REFUNDED')";
    private static final String FINISHED = "a.status IN ('COMPLETED', 'DEFAULTED')";
    /** Only a JSON array is cast; anything else (legacy text, '') contributes no factors instead of failing the query. */
    private static final String FACTORS =
            "CASE WHEN a.score_factors ~ '^\\s*\\[' THEN a.score_factors::jsonb ELSE '[]'::jsonb END";
    /**
     * Policy-overlay rules (credit-risk-engine PolicyOverlay, plus application-service's creditLimitUnavailable hold).
     * They add log-odds on top of the model, so they're taken back out before comparing with the model-only training
     * baseline - otherwise a loyal customer base would read as population drift.
     */
    private static final String POLICY_RULES =
            "'priorDefault', 'latePayments', 'completedPlans', 'amountToIncome', 'creditLimitUnavailable'";
    /** Sum of the policy rules' contributions for row a, as column o.overlay. */
    private static final String OVERLAY = " CROSS JOIN LATERAL (SELECT coalesce(sum((r->>'contribution')::float8), 0) AS overlay"
            + " FROM jsonb_array_elements(" + FACTORS + ") r WHERE r->>'feature' IN (" + POLICY_RULES + ")) o";
    /** The model's own probability: the decision score's log-odds minus the policy rules (clamped off 0 and 1). */
    private static final String MODEL_SCORE = "(1 / (1 + exp(-(ln(least(greatest(a.risk_score, 1e-9), 1 - 1e-9)"
            + " / (1 - least(greatest(a.risk_score, 1e-9), 1 - 1e-9))) - o.overlay))))";
    private static final String BIN = "least(floor(" + MODEL_SCORE + " * 10), 9)::int";

    private final NamedParameterJdbcTemplate jdbc;

    public ModelMonitoringQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long scored(String tz, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("SELECT count(*) FROM application.applications a WHERE a.risk_score IS NOT NULL AND "
                + CREATED, params(tz, from, to), Long.class);
    }

    public List<BinRow> scoreBins(String tz, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT " + BIN + " AS bin, count(*) AS n FROM application.applications a" + OVERLAY
                        + " WHERE a.risk_score IS NOT NULL AND " + CREATED + " GROUP BY 1",
                params(tz, from, to), (rs, i) -> new BinRow(rs.getInt("bin"), rs.getLong("n")));
    }

    public List<FactorRow> factors(String tz, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT f->>'feature' AS feature, count(*) AS n, avg((f->>'contribution')::float8) AS mean"
                        + " FROM application.applications a CROSS JOIN LATERAL jsonb_array_elements(" + FACTORS + ") f"
                        + " WHERE a.risk_score IS NOT NULL AND " + CREATED + " GROUP BY 1",
                params(tz, from, to),
                (rs, i) -> new FactorRow(rs.getString("feature"), rs.getLong("n"), rs.getDouble("mean")));
    }

    public List<OutcomeRow> outcomes(String tz, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT " + BIN + " AS bin, count(*) AS finished,"
                        + " count(*) FILTER (WHERE a.status = 'DEFAULTED') AS defaulted"
                        + " FROM application.applications a" + OVERLAY
                        + " WHERE a.risk_score IS NOT NULL AND " + FINISHED + " AND " + DECIDED + " GROUP BY 1",
                params(tz, from, to),
                (rs, i) -> new OutcomeRow(rs.getInt("bin"), rs.getLong("finished"), rs.getLong("defaulted")));
    }

    /**
     * Model lean on the decision score ops saw: < 0.5 approve, else decline. Reviews without a score (held while the
     * engine was down) have no lean and aren't counted. An ops approval later cancelled was still an approval.
     */
    public ReviewRow reviews(String tz, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("SELECT count(*) FILTER (WHERE a.status <> 'MANUAL_REVIEW' AND a.risk_score IS NOT NULL) AS decided,"
                        + " count(*) FILTER (WHERE ((" + APPROVED + " OR a.status = 'CANCELLED') AND a.risk_score < 0.5)"
                        + " OR (a.status = 'DECLINED' AND a.risk_score >= 0.5)) AS agreed"
                        + " FROM application.applications a WHERE a.decision_source = 'OPS' AND " + DECIDED,
                params(tz, from, to), (rs, i) -> new ReviewRow(rs.getLong("decided"), rs.getLong("agreed")));
    }

    public List<SourceOutcomeRow> outcomesBySource(String tz, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT a.decision_source AS source, count(*) AS finished,"
                        + " count(*) FILTER (WHERE a.status = 'DEFAULTED') AS defaulted"
                        + " FROM application.applications a"
                        + " WHERE a.decision_source IN ('OPS', 'MODEL') AND " + FINISHED + " AND " + DECIDED + " GROUP BY 1",
                params(tz, from, to),
                (rs, i) -> new SourceOutcomeRow(rs.getString("source"), rs.getLong("finished"), rs.getLong("defaulted")));
    }

    private static MapSqlParameterSource params(String tz, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource().addValue("tz", tz).addValue("from", from).addValue("to", to);
    }
}
