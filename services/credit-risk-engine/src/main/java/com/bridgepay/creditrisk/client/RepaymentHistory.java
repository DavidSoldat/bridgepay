package com.bridgepay.creditrisk.client;

/**
 * Returned by Repayment Reconciliation's
 * {@code GET /internal/repayment-history/{applicantId}} - the one genuinely
 * real (non-mocked) scoring input, per spec section 6.
 */
public record RepaymentHistory(
        int completedPlans,
        int defaultedPlans,
        int latePaymentCount,
        double onTimeRate
) {
}
