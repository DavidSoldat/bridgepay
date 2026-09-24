package com.bridgepay.application.dto;

import java.math.BigDecimal;

/**
 * All-time totals for the merchant dashboard tiles. approvalRate is
 * approved / (approved + declined) - in-review applications have no outcome
 * yet - and is null when nothing has been decided.
 */
public record MerchantSummaryResponse(
        long totalCheckouts,
        long approvedCount,
        long inReviewCount,
        long declinedCount,
        Double approvalRate,
        BigDecimal approvedVolume,
        BigDecimal feesPaid,
        BigDecimal netPaidOut
) {
}
