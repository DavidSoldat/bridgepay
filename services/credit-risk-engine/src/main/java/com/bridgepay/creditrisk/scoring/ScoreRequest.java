package com.bridgepay.creditrisk.scoring;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Body of {@code POST /internal/score} - field names match what
 * application-service's HttpCreditRiskClient sends (see spec section 11).
 */
public record ScoreRequest(
        @NotNull UUID applicantId,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String merchantCategory,
        @NotNull Instant requestedAt
) {
}
