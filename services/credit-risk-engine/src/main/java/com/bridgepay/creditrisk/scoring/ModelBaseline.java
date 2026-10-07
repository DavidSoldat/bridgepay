package com.bridgepay.creditrisk.scoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Training-side facts for model monitoring (scripts/build_monitoring_baseline.py). Served only while its
 * modelVersion matches the model actually loaded, so a retrain without a new baseline can't misreport drift.
 * Scoring never depends on this.
 */
@Component
public class ModelBaseline {

    private static final Logger log = LoggerFactory.getLogger(ModelBaseline.class);

    private final JsonNode content;

    public ModelBaseline(ResourceLoader resourceLoader, ObjectMapper objectMapper,
                         @Value("${bridgepay.credit-risk-model.model-path}") String modelPath,
                         @Value("${bridgepay.credit-risk-model.baseline-path}") String baselinePath) {
        this.content = load(resourceLoader, objectMapper, modelPath, baselinePath);
    }

    public Optional<JsonNode> content() {
        return Optional.ofNullable(content);
    }

    private static JsonNode load(ResourceLoader loader, ObjectMapper mapper, String modelPath, String baselinePath) {
        try (InputStream model = loader.getResource(modelPath).getInputStream();
             InputStream baseline = loader.getResource(baselinePath).getInputStream()) {
            String version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(model.readAllBytes()))
                    .substring(0, 12);
            JsonNode json = mapper.readTree(baseline);
            JsonNode expected = json.get("modelVersion");
            if (expected == null || !version.equals(expected.asString())) {
                log.error("Monitoring baseline {} is for model {}, but the loaded model is {} - rerun build_monitoring_baseline.py",
                        baselinePath, expected, version);
                return null;
            }
            return json;
        } catch (Exception e) {
            log.error("Monitoring baseline {} could not be loaded", baselinePath, e);
            return null;
        }
    }
}
