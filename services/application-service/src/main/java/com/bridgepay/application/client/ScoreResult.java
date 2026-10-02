package com.bridgepay.application.client;

import java.util.ArrayList;
import java.util.List;

public record ScoreResult(
        double riskScore,
        ScoreDecision decision,
        List<ScoreFactor> scoreFactors
) {

    public record ScoreFactor(String feature, double contribution) {
    }

    /**
     * Used when the Credit Risk Engine can't be reached or fails - per the
     * fail-safe rule, incomplete data always routes to manual review, never
     * an automatic approve or a hard failure of the checkout.
     */
    public static ScoreResult unavailableFallback() {
        return new ScoreResult(0.0, ScoreDecision.MANUAL_REVIEW, List.of());
    }

    /**
     * The spending limit couldn't be checked: never auto-approve on incomplete data. A decline or a
     * review stays as it is; the factor tells ops why an otherwise-approved order is waiting.
     */
    public ScoreResult withCreditLimitUnavailable() {
        List<ScoreFactor> factors = new ArrayList<>(scoreFactors);
        factors.add(new ScoreFactor("creditLimitUnavailable", 0.0));
        ScoreDecision held = decision == ScoreDecision.APPROVE ? ScoreDecision.MANUAL_REVIEW : decision;
        return new ScoreResult(riskScore, held, List.copyOf(factors));
    }
}
