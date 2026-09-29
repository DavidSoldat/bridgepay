package com.bridgepay.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Merchant Sales dashboard for one period (days ending today in the caller's time zone) and the period
 * before it. Applications count by order date, payouts by paid date; pendingPayout is a snapshot.
 */
public record MerchantDashboardResponse(
        int days,
        LocalDate from,
        LocalDate to,
        String bucket,
        PeriodTotals current,
        PeriodTotals previous,
        BigDecimal pendingPayout,
        List<SeriesPoint> series
) {
    public record PeriodTotals(long checkouts, long approved, long declined, long inReview, long paid,
                               BigDecimal approvedVolume, Double approvalRate,
                               BigDecimal feesPaid, BigDecimal netPaidOut) {
    }

    public record SeriesPoint(LocalDate start, long checkouts, BigDecimal approvedVolume) {
    }
}
