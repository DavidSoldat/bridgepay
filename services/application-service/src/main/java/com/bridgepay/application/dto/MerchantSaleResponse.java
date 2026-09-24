package com.bridgepay.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A checkout as a merchant sees it. Deliberately its own type rather than a
 * trimmed ApplicationResponse, so shopper credit data (applicantId,
 * riskScore, scoreFactors) can't leak to merchants by accident.
 */
public record MerchantSaleResponse(
        UUID id,
        Instant createdAt,
        BigDecimal amount,
        String status,
        Integer installmentCount,
        BigDecimal installmentAmount,
        Instant decisionAt
) {
}
