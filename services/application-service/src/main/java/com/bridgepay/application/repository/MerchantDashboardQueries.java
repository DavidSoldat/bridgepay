package com.bridgepay.application.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Aggregates for the merchant dashboard. Dates are local dates in {@code tz}, inclusive. */
@Repository
public class MerchantDashboardQueries {

    public record StatusRow(String status, long count, BigDecimal volume, long paid) {
    }

    public record BucketRow(LocalDate start, long checkouts, BigDecimal approvedVolume) {
    }

    public record PaidRow(BigDecimal fees, BigDecimal net) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public MerchantDashboardQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<StatusRow> statusTotals(UUID merchantId, String tz, LocalDate from, LocalDate to) {
        return jdbc.query("""
                        SELECT a.status, count(*) AS cnt, coalesce(sum(a.amount), 0) AS volume,
                               count(*) FILTER (WHERE p.status = 'PAID') AS paid
                        FROM application.applications a
                        LEFT JOIN application.merchant_payouts p ON p.application_id = a.id
                        WHERE a.merchant_id = :merchantId
                          AND (a.created_at AT TIME ZONE :tz)::date BETWEEN :from AND :to
                        GROUP BY a.status""",
                params(merchantId, tz, from, to),
                (rs, i) -> new StatusRow(rs.getString("status"), rs.getLong("cnt"),
                        rs.getBigDecimal("volume"), rs.getLong("paid")));
    }

    /** Non-empty buckets only; unit is 'day' or 'week' (ISO, Monday). The service fills the gaps. */
    public List<BucketRow> buckets(UUID merchantId, String tz, LocalDate from, LocalDate to, String unit) {
        return jdbc.query("""
                        SELECT date_trunc(:unit, a.created_at AT TIME ZONE :tz)::date AS start,
                               count(*) AS checkouts,
                               coalesce(sum(a.amount) FILTER (WHERE a.status IN ('APPROVED', 'COMPLETED', 'DEFAULTED', 'REFUND_PENDING')), 0)
                                   AS volume
                        FROM application.applications a
                        WHERE a.merchant_id = :merchantId
                          AND (a.created_at AT TIME ZONE :tz)::date BETWEEN :from AND :to
                        GROUP BY 1""",
                params(merchantId, tz, from, to).addValue("unit", unit),
                (rs, i) -> new BucketRow(rs.getObject("start", LocalDate.class), rs.getLong("checkouts"),
                        rs.getBigDecimal("volume")));
    }

    public PaidRow paidPayouts(UUID merchantId, String tz, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                        SELECT coalesce(sum(p.fee_amount), 0) AS fees, coalesce(sum(p.amount - p.fee_amount), 0) AS net
                        FROM application.merchant_payouts p
                        WHERE p.merchant_id = :merchantId AND p.status = 'PAID'
                          AND (p.paid_at AT TIME ZONE :tz)::date BETWEEN :from AND :to""",
                params(merchantId, tz, from, to),
                (rs, i) -> new PaidRow(rs.getBigDecimal("fees"), rs.getBigDecimal("net")));
    }

    public BigDecimal pendingNet(UUID merchantId) {
        return jdbc.queryForObject("""
                        SELECT coalesce(sum(amount - fee_amount), 0)
                        FROM application.merchant_payouts
                        WHERE merchant_id = :merchantId AND status = 'PENDING'""",
                new MapSqlParameterSource("merchantId", merchantId), BigDecimal.class);
    }

    private static MapSqlParameterSource params(UUID merchantId, String tz, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource()
                .addValue("merchantId", merchantId)
                .addValue("tz", tz)
                .addValue("from", from)
                .addValue("to", to);
    }
}
