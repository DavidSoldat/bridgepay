package com.bridgepay.creditbureau.pool;

/**
 * Give Me Some Credit-shaped profile. Field names/JSON shape must match
 * credit-risk-engine's client.BureauProfile exactly - these two records are
 * intentionally duplicated rather than shared, per this project's
 * no-shared-library-across-services convention (see CLAUDE.md).
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
