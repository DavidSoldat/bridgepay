package com.bridgepay.creditrisk.scoring;

public enum ScoreDecision {
    APPROVE,
    MANUAL_REVIEW,
    DECLINE;

    /** The one place the thresholds live; credit-limit bands and the model monitoring page read them too. */
    public static final double REVIEW_THRESHOLD = 0.3;
    public static final double DECLINE_THRESHOLD = 0.7;

    public static ScoreDecision forProbability(double probability) {
        if (probability < REVIEW_THRESHOLD) {
            return APPROVE;
        }
        if (probability < DECLINE_THRESHOLD) {
            return MANUAL_REVIEW;
        }
        return DECLINE;
    }
}
