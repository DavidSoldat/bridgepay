package com.bridgepay.creditrisk.web;

import com.bridgepay.creditrisk.scoring.CreditLimitResponse;
import com.bridgepay.creditrisk.scoring.ScoreRequest;
import com.bridgepay.creditrisk.scoring.ScoreResponse;
import com.bridgepay.creditrisk.scoring.ScoringService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;


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

    @GetMapping("/internal/credit-limit/{applicantId}")
    public CreditLimitResponse creditLimit(@PathVariable UUID applicantId) {
        return scoringService.creditLimit(applicantId);
    }
}
