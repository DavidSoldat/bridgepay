package com.bridgepay.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MerchantPayoutResponse(
        UUID id,
        UUID applicationId,
        BigDecimal amount,
        BigDecimal feeAmount,
        String status,
        Instant paidAt
) {
}
