package com.bridgepay.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Ops view of one of a shopper's applications. Ops-only: carries the reviewer's username. */
public record ApplicantApplicationResponse(
        UUID applicationId,
        UUID merchantId,
        String merchantName,
        BigDecimal amount,
        String status,
        String decisionSource,
        String decidedBy,
        Instant createdAt,
        Instant decisionAt
) {
}
