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

import java.util.Map;

/**
 * Assembles what's needed and scores it - the Credit Risk Engine owns its own
 * calls to the Mock Bureau and Repayment History rather than Application
 * Service pre-fetching them (spec section 11's orchestration decision).
 * <p>
 * Fail-safe rule: any failure anywhere in this pipeline - bureau call,
 * repayment history call, or the ONNX inference itself - routes to
 * MANUAL_REVIEW. Never auto-approve or hard-fail the checkout on incomplete
 * data.
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
            return toResponse(outcome);
        } catch (Exception ex) {
            log.warn("Scoring failed for applicant {}, defaulting to MANUAL_REVIEW: {}",
                    request.applicantId(), ex.getMessage());
            return ScoreResponse.manualReviewFallback();
        }
    }

    private ScoreResponse toResponse(ScoreOutcome outcome) {
        return new ScoreResponse(outcome.probability(), decisionFor(outcome.probability()), outcome.factors());
    }

    private ScoreDecision decisionFor(double probability) {
        if (probability < 0.3) {
            return ScoreDecision.APPROVE;
        }
        if (probability < 0.7) {
            return ScoreDecision.MANUAL_REVIEW;
        }
        return ScoreDecision.DECLINE;
    }
}
