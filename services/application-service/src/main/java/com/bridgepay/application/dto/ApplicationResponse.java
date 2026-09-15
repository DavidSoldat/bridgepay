package com.bridgepay.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ApplicationResponse(
        UUID applicationId,
        String status,
        Integer installmentCount,
        BigDecimal installmentAmount,
        Instant decisionAt
) {
}
