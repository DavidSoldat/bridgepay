package com.bridgepay.repayment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record InstallmentResponse(
        int sequenceNumber,
        LocalDate dueDate,
        BigDecimal amount,
        String status,
        Instant paidAt
) {
}
