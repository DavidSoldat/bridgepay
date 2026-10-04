package com.bridgepay.application.service;

import com.bridgepay.application.domain.ApplicationStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

/** A merchant's sales joined to their payouts, as one CSV for their bookkeeping. */
@Component
public class SalesCsv {

    static final String HEADER = "order_id,created_at,amount,installments,status,decided_at,fee,net,payout_status,paid_at";

    private final NamedParameterJdbcTemplate jdbc;

    public SalesCsv(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ponytail: builds the whole file in memory with no date range; stream it and add from/to once a merchant
    // has thousands of orders.
    @Transactional(readOnly = true)
    public String export(UUID merchantId, String status) {
        boolean all = "ALL".equalsIgnoreCase(status);
        MapSqlParameterSource params = new MapSqlParameterSource("merchantId", merchantId);
        if (!all) {
            params.addValue("status", ApplicationStatus.valueOf(status).name());   // unknown -> IAE -> 400
        }
        StringBuilder csv = new StringBuilder(HEADER).append("\r\n");
        String sql = "SELECT a.id, a.created_at, a.amount, a.installment_count, a.status, a.decision_at, "
                + "p.fee_amount, p.amount AS payout_amount, p.status AS payout_status, p.paid_at "
                + "FROM application.applications a "
                + "LEFT JOIN application.merchant_payouts p ON p.application_id = a.id "
                + "WHERE a.merchant_id = :merchantId" + (all ? "" : " AND a.status = :status")
                + " ORDER BY a.created_at DESC";
        jdbc.query(sql, params, (ResultSet rs) -> {
            BigDecimal fee = rs.getBigDecimal("fee_amount");
            BigDecimal payout = rs.getBigDecimal("payout_amount");
            csv.append(String.join(",",
                    cell(rs.getString("id")),
                    cell(instant(rs, "created_at")),
                    cell(rs.getBigDecimal("amount").toPlainString()),
                    cell(rs.getObject("installment_count") == null ? null : rs.getString("installment_count")),
                    cell(rs.getString("status")),
                    cell(instant(rs, "decision_at")),
                    cell(fee == null ? null : fee.toPlainString()),
                    cell(fee == null ? null : payout.subtract(fee).toPlainString()),
                    cell(rs.getString("payout_status")),
                    cell(instant(rs, "paid_at"))))
                    .append("\r\n");
        });
        return csv.toString();
    }

    private static String instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant().toString();
    }

    /** RFC 4180 quoting, plus a leading ' on anything a spreadsheet would run as a formula. */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")
                ? "\"" + safe.replace("\"", "\"\"") + "\""
                : safe;
    }
}
