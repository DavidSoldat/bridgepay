package com.bridgepay.creditrisk.scoring;

import java.util.Map;

/**
 * Port to the loaded credit risk model. OnnxModelScorer is the only real
 * implementation; this interface exists so ScoringService's fail-safe
 * behavior can be unit-tested without a real ONNX model loaded.
 */
interface ModelScorer {
    ScoreOutcome score(Map<String, Double> features);
}
