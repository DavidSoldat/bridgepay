package com.bridgepay.creditrisk.scoring;

import java.util.Map;

/**
 * Deserialized shape of coefficients.json - fitted StandardScaler params plus
 * LogisticRegression coefficients, exported alongside the ONNX model (spec
 * section 12). Used only to compute the scoreFactors breakdown as a plain dot
 * product; the actual prediction comes from the ONNX inference call.
 * <p>
 * {@code features} is declared as a {@link Map} (deserialized as a
 * LinkedHashMap, preserving JSON key order) rather than a fixed set of named
 * fields, because the exact feature set/order is decided at training time
 * (spec section 12, scripts/train_model.py). This same order also drives
 * the ONNX input tensor's column order in OnnxModelScorer.
 */
public record CoefficientSet(double intercept, Map<String, FeatureCoefficient> features) {

    public record FeatureCoefficient(double mean, double scale, double coefficient) {
    }
}
