package com.bridgepay.repayment.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record RepaymentPlanResponse(
        UUID planId,
        UUID applicationId,
        String status,
        BigDecimal totalAmount,
        int installmentCount,
        BigDecimal installmentAmount,
        List<InstallmentResponse> installments,
        String checkoutTransactionId
) {
}
