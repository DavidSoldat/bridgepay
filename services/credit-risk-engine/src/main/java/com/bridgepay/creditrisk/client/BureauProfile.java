package com.bridgepay.creditrisk.client;

/**
 * Give Me Some Credit-shaped profile returned by the Mock Credit Bureau's
 * {@code GET /internal/bureau-profile/{applicantId}} - see spec section 6
 * (Mock Credit Bureau Mechanics) and section 12 (ML Plan / dataset columns).
 */
public record BureauProfile(
        double revolvingUtilizationOfUnsecuredLines,
        int age,
        int numberOfTime30to59DaysPastDueNotWorse,
        double debtRatio,
        double monthlyIncome,
        int numberOfOpenCreditLinesAndLoans,
        int numberOfTimes90DaysLate,
        int numberRealEstateLoansOrLines,
        int numberOfTime60to89DaysPastDueNotWorse,
        int numberOfDependents
) {
}
