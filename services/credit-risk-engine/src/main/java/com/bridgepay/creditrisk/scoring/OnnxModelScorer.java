package com.bridgepay.creditrisk.scoring;

import ai.onnxruntime.OnnxMap;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Loads the ONNX-exported scikit-learn pipeline (StandardScaler +
 * LogisticRegression, see spec section 12) and the accompanying
 * coefficients.json at startup, then serves scoring calls against it.
 * <p>
 * The trained model doesn't exist yet (§12 "Train the actual model" is a
 * later PROGRESS.md task) - if the configured model/coefficients resources
 * aren't present, this component logs a warning and marks itself
 * unavailable rather than failing startup, so the service can be deployed
 * ahead of the model and every request fail-safes to MANUAL_REVIEW until the
 * real artifacts are dropped into place (no redeploy needed, just a restart).
 * The same fail-safe applies to any unexpected exception during inference.
 */
@Component
public class OnnxModelScorer implements ModelScorer {

    private static final Logger log = LoggerFactory.getLogger(OnnxModelScorer.class);

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final String modelPath;
    private final String coefficientsPath;

    private volatile boolean available = false;
    private OrtEnvironment environment;
    private OrtSession session;
    private CoefficientSet coefficients;
    private List<String> orderedFeatureNames;

    public OnnxModelScorer(ResourceLoader resourceLoader,
                            ObjectMapper objectMapper,
                            @Value("${bridgepay.credit-risk-model.model-path}") String modelPath,
                            @Value("${bridgepay.credit-risk-model.coefficients-path}") String coefficientsPath) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.modelPath = modelPath;
        this.coefficientsPath = coefficientsPath;
    }

    @PostConstruct
    void loadModel() {
        Resource modelResource = resourceLoader.getResource(modelPath);
        Resource coefficientsResource = resourceLoader.getResource(coefficientsPath);
        if (!modelResource.exists() || !coefficientsResource.exists()) {
            log.warn("Credit risk model not found at {} / {} - every /internal/score call will "
                    + "fail-safe to MANUAL_REVIEW until the trained model is deployed here.",
                    modelPath, coefficientsPath);
            return;
        }

        try {
            coefficients = objectMapper.readValue(coefficientsResource.getInputStream(), CoefficientSet.class);
            orderedFeatureNames = List.copyOf(coefficients.features().keySet());

            byte[] modelBytes = modelResource.getInputStream().readAllBytes();
            environment = OrtEnvironment.getEnvironment();
            session = environment.createSession(modelBytes, new OrtSession.SessionOptions());
            available = true;
            log.info("Loaded credit risk model from {} with {} features", modelPath, orderedFeatureNames.size());
        } catch (Exception ex) {
            log.warn("Failed to load credit risk model, falling back to MANUAL_REVIEW for all requests", ex);
            available = false;
        }
    }

    @Override
    public ScoreOutcome score(Map<String, Double> features) {
        if (!available) {
            throw new ModelUnavailableException("Credit risk model is not loaded");
        }

        float[] inputRow = new float[orderedFeatureNames.size()];
        for (int i = 0; i < orderedFeatureNames.size(); i++) {
            String name = orderedFeatureNames.get(i);
            Double value = features.get(name);
            if (value == null) {
                throw new ModelUnavailableException(
                        "Feature '" + name + "' required by coefficients.json is missing from the computed feature vector");
            }
            inputRow[i] = value.floatValue();
        }

        double probability;
        try (OnnxTensor input = OnnxTensor.createTensor(environment, new float[][]{inputRow});
             OrtSession.Result result = session.run(Map.of(session.getInputNames().iterator().next(), input))) {
            probability = extractPositiveClassProbability(result);
        } catch (OrtException ex) {
            throw new ModelUnavailableException("ONNX inference call failed", ex);
        }

        return new ScoreOutcome(probability, computeScoreFactors(features));
    }

    private double extractPositiveClassProbability(OrtSession.Result result) {
        for (Map.Entry<String, OnnxValue> entry : result) {
            Object value;
            try {
                value = entry.getValue().getValue();
            } catch (OrtException ex) {
                continue;
            }

            if (value instanceof List<?> sequence && !sequence.isEmpty()) {
                Double probability = probabilityFromSequenceElement(sequence.get(0));
                if (probability != null) {
                    return probability;
                }
            } else if (value instanceof float[][] batch && batch.length > 0 && batch[0].length > 1) {
                return batch[0][1];
            }
        }
        throw new ModelUnavailableException("ONNX model output did not contain a recognizable class-1 probability");
    }

    /**
     * skl2onnx's default classifier export (zipmap on) produces a sequence
     * of one {@link OnnxMap} per row - not a plain {@link Map} - confirmed
     * against the real onnxruntime-java output shape via
     * OnnxModelScorerIntegrationTest, not assumed from documentation alone.
     */
    private Double probabilityFromSequenceElement(Object element) {
        Map<?, ?> classProbabilities;
        if (element instanceof OnnxMap onnxMap) {
            try {
                classProbabilities = (Map<?, ?>) onnxMap.getValue();
            } catch (OrtException ex) {
                return null;
            }
        } else if (element instanceof Map<?, ?> map) {
            classProbabilities = map;
        } else {
            return null;
        }

        for (Map.Entry<?, ?> entry : classProbabilities.entrySet()) {
            if (entry.getKey() instanceof Number key && key.intValue() == 1 && entry.getValue() instanceof Number value) {
                return value.doubleValue();
            }
        }
        return null;
    }

    /**
     * Exact per-feature contribution (coefficient * scaled value), not an
     * approximation - see coefficients.json's format and spec section 12.
     * Sorted by absolute impact so the ops dashboard can show the strongest
     * drivers of a decision first.
     */
    private List<ScoreFactor> computeScoreFactors(Map<String, Double> features) {
        List<ScoreFactor> factors = new ArrayList<>(orderedFeatureNames.size());
        for (String name : orderedFeatureNames) {
            CoefficientSet.FeatureCoefficient coefficient = coefficients.features().get(name);
            double scaled = (features.get(name) - coefficient.mean()) / coefficient.scale();
            factors.add(new ScoreFactor(name, coefficient.coefficient() * scaled));
        }
        factors.sort(Comparator.comparingDouble((ScoreFactor f) -> Math.abs(f.contribution())).reversed());
        return factors;
    }
}
