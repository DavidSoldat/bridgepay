package com.bridgepay.creditrisk.scoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Runs real ONNX Runtime inference against a toy fixture (see
 * src/test/resources/fixtures/generate_fixture.py) - the real trained model
 * doesn't exist yet (spec section 12, PROGRESS.md), so this is the only way
 * to verify the tensor I/O and output-parsing logic actually works against
 * onnxruntime's real output shape rather than a guess.
 */
class OnnxModelScorerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OnnxModelScorer scorer;
    private CoefficientSet coefficients;

    @BeforeEach
    void setUp() throws Exception {
        DefaultResourceLoader loader = new DefaultResourceLoader();
        scorer = new OnnxModelScorer(loader, MAPPER, "classpath:fixtures/model.onnx", "classpath:fixtures/coefficients.json");
        scorer.loadModel();
        coefficients = MAPPER.readValue(
                loader.getResource("classpath:fixtures/coefficients.json").getInputStream(), CoefficientSet.class);
    }

    @Test
    void score_onnxProbabilityMatchesTheManualLogisticRegressionDotProduct() {
        Map<String, Double> features = Map.of(
                "revolvingUtilization", 0.5,
                "age", 40.0,
                "debtRatio", 0.3,
                "monthlyIncome", 5000.0);

        ScoreOutcome outcome = scorer.score(features);

        double logitFromFactors = coefficients.intercept()
                + outcome.factors().stream().mapToDouble(ScoreFactor::contribution).sum();
        double probabilityFromFactors = 1 / (1 + Math.exp(-logitFromFactors));

        assertThat(outcome.factors()).hasSize(4);
        assertThat(outcome.probability()).isCloseTo(probabilityFromFactors, within(1e-4));
    }

    @Test
    void score_throwsModelUnavailable_whenModelFilesAreMissing() {
        // src/main/resources/model/ is intentionally empty until §12 training
        // produces a real model - see that directory's README.
        OnnxModelScorer missing = new OnnxModelScorer(new DefaultResourceLoader(), MAPPER,
                "classpath:model/model.onnx", "classpath:model/coefficients.json");
        missing.loadModel();

        assertThatThrownBy(() -> missing.score(Map.of()))
                .isInstanceOf(ModelUnavailableException.class);
    }
}
