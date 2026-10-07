package com.bridgepay.creditrisk.web;

import com.bridgepay.creditrisk.scoring.ModelBaseline;
import com.bridgepay.creditrisk.scoring.ScoreDecision;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@RestController
public class ModelController {

    private final ModelBaseline baseline;

    public ModelController(ModelBaseline baseline) {
        this.baseline = baseline;
    }

    /** Training baseline for the ops model monitoring page, plus the thresholds ScoreDecision applies. */
    @GetMapping("/api/v1/model")
    @PreAuthorize("hasRole('OPS')")
    public JsonNode model() {
        ObjectNode out = ((ObjectNode) baseline.content().orElseThrow(BaselineUnavailableException::new)).deepCopy();
        out.putObject("thresholds")
                .put("review", ScoreDecision.REVIEW_THRESHOLD)
                .put("decline", ScoreDecision.DECLINE_THRESHOLD);
        return out;
    }

    public static class BaselineUnavailableException extends RuntimeException {
        BaselineUnavailableException() {
            super("Model monitoring baseline is unavailable");
        }
    }
}
