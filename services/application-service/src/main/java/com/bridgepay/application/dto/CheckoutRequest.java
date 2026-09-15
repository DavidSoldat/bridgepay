package com.bridgepay.application.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CheckoutRequest(
        @NotNull(message = "merchantId is required")
        UUID merchantId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "1.00", message = "amount must be at least 1.00")
        @DecimalMax(value = "5000.00", message = "amount must not exceed 5000.00")
        BigDecimal amount
) {
}
