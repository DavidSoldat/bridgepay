package com.bridgepay.application.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ScoreRequest(
        UUID applicantId,
        BigDecimal amount,
        String merchantCategory,
        Instant requestedAt
) {
}
