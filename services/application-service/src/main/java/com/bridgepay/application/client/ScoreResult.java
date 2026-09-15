package com.bridgepay.application.client;

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
}
