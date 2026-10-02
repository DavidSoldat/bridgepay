package com.bridgepay.creditrisk.scoring;

import com.bridgepay.creditrisk.client.BureauProfile;
import com.bridgepay.creditrisk.client.RepaymentHistory;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assembles every scoreable value from the bureau profile, on-platform
 * repayment history, and checkout context into one named map. Which of these
 * names actually get used, and in what order, is decided by coefficients.json
 * at runtime (see CoefficientSet) - not every name here is guaranteed to be
 * consumed by the model that's actually loaded.
 * <p>
 * merchantCategory is deliberately not encoded into a numeric feature yet -
 * the encoding scheme is a training-time decision (spec section 12), not
 * something to guess ahead of the real dataset work.
 */
public final class FeatureVector {

    private FeatureVector() {
    }

    public static Map<String, Double> from(BureauProfile bureau, RepaymentHistory history, ScoreRequest request) {
        Map<String, Double> features = from(bureau, history);
        features.put("requestedAmount", request.amount().doubleValue());
        features.put("hourOfDay", (double) request.requestedAt().atZone(ZoneOffset.UTC).getHour());
        return features;
    }

    /** Everything except checkout context - used by the credit-limit check, which has no order yet. */
    public static Map<String, Double> from(BureauProfile bureau, RepaymentHistory history) {
        Map<String, Double> features = new LinkedHashMap<>();
        features.put("revolvingUtilization", bureau.revolvingUtilizationOfUnsecuredLines());
        features.put("age", (double) bureau.age());
        features.put("numberOfTime30to59DaysPastDueNotWorse", (double) bureau.numberOfTime30to59DaysPastDueNotWorse());
        features.put("debtRatio", bureau.debtRatio());
        features.put("monthlyIncome", bureau.monthlyIncome());
        features.put("numberOfOpenCreditLinesAndLoans", (double) bureau.numberOfOpenCreditLinesAndLoans());
        features.put("numberOfTimes90DaysLate", (double) bureau.numberOfTimes90DaysLate());
        features.put("numberRealEstateLoansOrLines", (double) bureau.numberRealEstateLoansOrLines());
        features.put("numberOfTime60to89DaysPastDueNotWorse", (double) bureau.numberOfTime60to89DaysPastDueNotWorse());
        features.put("numberOfDependents", (double) bureau.numberOfDependents());
        features.put("completedPlans", (double) history.completedPlans());
        features.put("defaultedPlans", (double) history.defaultedPlans());
        features.put("latePaymentCount", (double) history.latePaymentCount());
        features.put("onTimeRate", history.onTimeRate());
        return features;
    }
}
