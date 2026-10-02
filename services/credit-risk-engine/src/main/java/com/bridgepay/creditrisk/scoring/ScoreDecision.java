package com.bridgepay.creditrisk.scoring;

public enum ScoreDecision {
    APPROVE,
    MANUAL_REVIEW,
    DECLINE;

    /** The one place the 0.3 / 0.7 thresholds live; credit-limit bands are read off the same function. */
    public static ScoreDecision forProbability(double probability) {
        if (probability < 0.3) {
            return APPROVE;
        }
        if (probability < 0.7) {
            return MANUAL_REVIEW;
        }
        return DECLINE;
    }
}
