package com.bridgepay.application.dto;

import com.bridgepay.application.client.ScoreResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApplicationResponse(
        UUID applicationId,
        UUID applicantId,
        UUID merchantId,
        BigDecimal amount,
        String status,
        Double riskScore,
        List<ScoreResult.ScoreFactor> scoreFactors,
        Integer installmentCount,
        BigDecimal installmentAmount,
        Instant decisionAt
) {
}
