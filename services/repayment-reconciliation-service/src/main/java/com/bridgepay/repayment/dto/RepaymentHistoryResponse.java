package com.bridgepay.repayment.dto;

public record RepaymentHistoryResponse(
        int completedPlans,
        int defaultedPlans,
        int latePaymentCount,
        double onTimeRate
) {
}
