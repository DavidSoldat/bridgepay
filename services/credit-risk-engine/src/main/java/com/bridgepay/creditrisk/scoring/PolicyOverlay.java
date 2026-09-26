package com.bridgepay.creditrisk.scoring;

import com.bridgepay.creditrisk.client.RepaymentHistory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Rule-based adjustments layered on top of the model's logit, in the same
 * log-odds units as the model's own scoreFactors - so fired rules render in
 * the ops review chart next to the bureau factors. Exists because the
 * trained model only knows the 10 bureau features; on-platform repayment
 * history (spec section 6's one genuinely real input) can't be trained on
 * from the Kaggle data.
 * <p>
 * A rule that doesn't fire emits no factor.
 */
final class PolicyOverlay {

    // ponytail: hand-set weights as constants; move to a policy.json beside
    // coefficients.json if they ever need tuning without a code change.
    private static final double PRIOR_DEFAULT = 3.0;
    private static final double PER_LATE_PAYMENT = 0.6;
    private static final double LATE_PAYMENTS_CAP = 2.4;
    private static final double PER_COMPLETED_PLAN = -0.4;
    private static final double COMPLETED_PLANS_CAP = -1.2;
    private static final double AMOUNT_TO_INCOME = 1.0;
    private static final double AMOUNT_TO_INCOME_THRESHOLD = 0.5;

    record Result(double logit, List<ScoreFactor> factors, boolean forceDecline) {
    }

    private PolicyOverlay() {
    }

    static Result apply(double logit, RepaymentHistory history, double monthlyIncome, BigDecimal amount) {
        List<ScoreFactor> factors = new ArrayList<>();
        if (history.defaultedPlans() > 0) {
            factors.add(new ScoreFactor("priorDefault", PRIOR_DEFAULT));
        }
        if (history.latePaymentCount() > 0) {
            factors.add(new ScoreFactor("latePayments",
                    Math.min(LATE_PAYMENTS_CAP, PER_LATE_PAYMENT * history.latePaymentCount())));
        }
        if (history.completedPlans() > 0) {
            factors.add(new ScoreFactor("completedPlans",
                    Math.max(COMPLETED_PLANS_CAP, PER_COMPLETED_PLAN * history.completedPlans())));
        }
        if (monthlyIncome <= 0 || amount.doubleValue() / monthlyIncome > AMOUNT_TO_INCOME_THRESHOLD) {
            factors.add(new ScoreFactor("amountToIncome", AMOUNT_TO_INCOME));
        }
        double adjusted = logit + factors.stream().mapToDouble(ScoreFactor::contribution).sum();
        return new Result(adjusted, List.copyOf(factors), history.defaultedPlans() > 0);
    }
}
