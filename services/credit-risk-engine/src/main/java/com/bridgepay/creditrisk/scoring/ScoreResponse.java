package com.bridgepay.creditrisk.scoring;

import java.io.Serializable;
import java.util.List;

public record ScoreResponse(
        double riskScore,
        ScoreDecision decision,
        List<ScoreFactor> scoreFactors
) implements Serializable {

    /**
     * Used whenever the bureau/repayment-history calls or the ONNX inference
     * fail - per the spec's fail-safe rule, incomplete data always routes to
     * manual review, never an automatic approve or a hard failure.
     */
    public static ScoreResponse manualReviewFallback() {
        return new ScoreResponse(0.0, ScoreDecision.MANUAL_REVIEW, List.of());
    }
}
