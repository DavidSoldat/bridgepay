package com.bridgepay.creditrisk.scoring;

import com.bridgepay.creditrisk.client.BureauClient;
import com.bridgepay.creditrisk.client.BureauProfile;
import com.bridgepay.creditrisk.client.RepaymentHistory;
import com.bridgepay.creditrisk.client.RepaymentHistoryClient;
import com.bridgepay.creditrisk.config.CacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles what's needed and scores it, then applies PolicyOverlay's
 * rule-based log-odds adjustments (on-platform repayment history,
 * amount-to-income) - the Credit Risk Engine owns its own
 * calls to the Mock Bureau and Repayment History rather than Application
 * Service pre-fetching them (spec section 11's orchestration decision).
 * <p>
 * Fail-safe rule: any failure anywhere in this pipeline - bureau call,
 * repayment history call, or the ONNX inference itself - routes to
 * MANUAL_REVIEW. Never auto-approve or hard-fail the checkout on incomplete
 * data. The credit-limit check is the exception: it throws, so the caller can
 * tell "no limit" from "couldn't check".
 */
@Service
public class ScoringService {

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);

    private final BureauClient bureauClient;
    private final RepaymentHistoryClient repaymentHistoryClient;
    private final ModelScorer modelScorer;

    public ScoringService(BureauClient bureauClient,
                           RepaymentHistoryClient repaymentHistoryClient,
                           ModelScorer modelScorer) {
        this.bureauClient = bureauClient;
        this.repaymentHistoryClient = repaymentHistoryClient;
        this.modelScorer = modelScorer;
    }

    @Cacheable(cacheNames = CacheConfig.CREDIT_SCORE_CACHE,
            key = "#root.args[0].applicantId() + ':' + #root.args[0].amount() + ':' + #root.args[0].merchantCategory()")
    public ScoreResponse score(ScoreRequest request) {
        try {
            BureauProfile profile = bureauClient.fetchProfile(request.applicantId());
            RepaymentHistory history = repaymentHistoryClient.fetchHistory(request.applicantId());
            Map<String, Double> features = FeatureVector.from(profile, history, request);
            ScoreOutcome outcome = modelScorer.score(features);
            PolicyOverlay.Result overlay = PolicyOverlay.apply(
                    logit(outcome.probability()), history, profile.monthlyIncome(), request.amount());
            return toResponse(outcome, overlay);
        } catch (Exception ex) {
            log.warn("Scoring failed for applicant {}, defaulting to MANUAL_REVIEW: {}",
                    request.applicantId(), ex.getMessage());
            return ScoreResponse.manualReviewFallback();
        }
    }

    @Cacheable(cacheNames = CacheConfig.CREDIT_LIMIT_CACHE, key = "#root.args[0].toString()")
    public CreditLimitResponse creditLimit(UUID applicantId) {
        BureauProfile profile = bureauClient.fetchProfile(applicantId);
        RepaymentHistory history = repaymentHistoryClient.fetchHistory(applicantId);
        ScoreOutcome outcome = modelScorer.score(FeatureVector.from(profile, history));
        PolicyOverlay.Result overlay = PolicyOverlay.apply(
                logit(outcome.probability()), history, profile.monthlyIncome(), null);
        ScoreDecision decision = overlay.forceDecline()
                ? ScoreDecision.DECLINE
                : ScoreDecision.forProbability(probability(outcome, overlay));
        return CreditLimitResponse.of(decision, profile.monthlyIncome());
    }

    private ScoreResponse toResponse(ScoreOutcome outcome, PolicyOverlay.Result overlay) {
        double probability = probability(outcome, overlay);
        ScoreDecision decision = overlay.forceDecline() ? ScoreDecision.DECLINE : ScoreDecision.forProbability(probability);
        List<ScoreFactor> factors = new ArrayList<>(outcome.factors());
        factors.addAll(overlay.factors());
        return new ScoreResponse(probability, decision, List.copyOf(factors));
    }

    // No rule fired -> return the model's probability untouched, avoiding logit/sigmoid round-trip drift.
    private static double probability(ScoreOutcome outcome, PolicyOverlay.Result overlay) {
        return overlay.factors().isEmpty() ? outcome.probability() : sigmoid(overlay.logit());
    }

    private static double logit(double probability) {
        double p = Math.clamp(probability, 1e-9, 1 - 1e-9);
        return Math.log(p / (1 - p));
    }

    private static double sigmoid(double logit) {
        return 1 / (1 + Math.exp(-logit));
    }
}
