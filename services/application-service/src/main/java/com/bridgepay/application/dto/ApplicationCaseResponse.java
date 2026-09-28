package com.bridgepay.application.dto;

import com.bridgepay.application.client.ScoreResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Ops-only view of one application: the decision record, the merchant and the payout. */
public record ApplicationCaseResponse(
        UUID applicationId,
        UUID applicantId,
        BigDecimal amount,
        String status,
        Double riskScore,
        List<ScoreResult.ScoreFactor> scoreFactors,
        Integer installmentCount,
        BigDecimal installmentAmount,
        Instant createdAt,
        Instant decisionAt,
        Decision decision,
        MerchantInfo merchant,
        PayoutInfo payout
) {
    /** Null while the application awaits a decision; {@code source} null = decided before decision records existed. */
    public record Decision(String source, String decidedBy, String reviewerNote) {
    }

    public record MerchantInfo(UUID id, String name, BigDecimal feeRatePct) {
    }

    public record PayoutInfo(BigDecimal amount, BigDecimal feeAmount, BigDecimal netAmount, String status,
                             Instant paidAt) {
    }
}
