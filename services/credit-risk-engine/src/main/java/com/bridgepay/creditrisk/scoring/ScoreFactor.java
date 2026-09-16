package com.bridgepay.creditrisk.scoring;

import java.io.Serializable;

/**
 * One line of the explainability breakdown: {@code coefficient * scaled
 * feature value}, computed from coefficients.json - see OnnxModelScorer.
 * Exact per-feature contribution, not an approximation (spec section 12).
 */
public record ScoreFactor(String feature, double contribution) implements Serializable {
}
