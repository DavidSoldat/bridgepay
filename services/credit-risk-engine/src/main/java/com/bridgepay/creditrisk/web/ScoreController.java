package com.bridgepay.creditrisk.web;

import com.bridgepay.creditrisk.scoring.ScoreRequest;
import com.bridgepay.creditrisk.scoring.ScoreResponse;
import com.bridgepay.creditrisk.scoring.ScoringService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class ScoreController {

    private final ScoringService scoringService;

    public ScoreController(ScoringService scoringService) {
        this.scoringService = scoringService;
    }

    @PostMapping("/internal/score")
    public ScoreResponse score(@Valid @RequestBody ScoreRequest request) {
        return scoringService.score(request);
    }
}
