package com.bridgepay.creditrisk.scoring;

import com.bridgepay.creditrisk.client.BureauClient;
import com.bridgepay.creditrisk.client.BureauProfile;
import com.bridgepay.creditrisk.client.BureauUnavailableException;
import com.bridgepay.creditrisk.client.RepaymentHistory;
import com.bridgepay.creditrisk.client.RepaymentHistoryClient;
import com.bridgepay.creditrisk.client.RepaymentHistoryUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScoringServiceTest {

    @Mock
    private BureauClient bureauClient;
    @Mock
    private RepaymentHistoryClient repaymentHistoryClient;
    @Mock
    private ModelScorer modelScorer;

    private ScoringService scoringService() {
        return new ScoringService(bureauClient, repaymentHistoryClient, modelScorer);
    }

    private ScoreRequest sampleRequest() {
        return new ScoreRequest(UUID.randomUUID(), new BigDecimal("199.99"), "general", Instant.now());
    }

    private BureauProfile sampleProfile() {
        return new BureauProfile(0.4, 35, 0, 0.3, 5000.0, 5, 0, 1, 0, 0);
    }

    @Test
    void score_fallsBackToManualReview_whenBureauCallFails() {
        when(bureauClient.fetchProfile(any())).thenThrow(new BureauUnavailableException("down", null));

        ScoreResponse response = scoringService().score(sampleRequest());

        assertThat(response.decision()).isEqualTo(ScoreDecision.MANUAL_REVIEW);
        assertThat(response.riskScore()).isZero();
        assertThat(response.scoreFactors()).isEmpty();
        verifyNoInteractions(repaymentHistoryClient, modelScorer);
    }

    @Test
    void score_fallsBackToManualReview_whenRepaymentHistoryCallFails() {
        when(bureauClient.fetchProfile(any())).thenReturn(sampleProfile());
        when(repaymentHistoryClient.fetchHistory(any()))
                .thenThrow(new RepaymentHistoryUnavailableException("down", null));

        ScoreResponse response = scoringService().score(sampleRequest());

        assertThat(response.decision()).isEqualTo(ScoreDecision.MANUAL_REVIEW);
        verify(modelScorer, never()).score(any());
    }

    @Test
    void score_fallsBackToManualReview_whenModelIsUnavailable() {
        when(bureauClient.fetchProfile(any())).thenReturn(sampleProfile());
        when(repaymentHistoryClient.fetchHistory(any())).thenReturn(new RepaymentHistory(0, 0, 0, 1.0));
        when(modelScorer.score(any())).thenThrow(new ModelUnavailableException("no model loaded"));

        ScoreResponse response = scoringService().score(sampleRequest());

        assertThat(response.decision()).isEqualTo(ScoreDecision.MANUAL_REVIEW);
    }

    @ParameterizedTest
    @CsvSource({
            "0.10, APPROVE",
            "0.29, APPROVE",
            "0.30, MANUAL_REVIEW",
            "0.69, MANUAL_REVIEW",
            "0.70, DECLINE",
            "0.95, DECLINE",
    })
    void score_mapsProbabilityToTheCorrectDecisionBand(double probability, ScoreDecision expected) {
        when(bureauClient.fetchProfile(any())).thenReturn(sampleProfile());
        when(repaymentHistoryClient.fetchHistory(any())).thenReturn(new RepaymentHistory(0, 0, 0, 1.0));
        when(modelScorer.score(any())).thenReturn(new ScoreOutcome(probability, List.of(new ScoreFactor("age", -0.1))));

        ScoreResponse response = scoringService().score(sampleRequest());

        assertThat(response.decision()).isEqualTo(expected);
        assertThat(response.riskScore()).isEqualTo(probability);
        assertThat(response.scoreFactors()).containsExactly(new ScoreFactor("age", -0.1));
    }

    @Test
    void score_passesTheAssembledFeatureVectorToTheModelScorer() {
        BureauProfile profile = sampleProfile();
        RepaymentHistory history = new RepaymentHistory(2, 0, 1, 0.9);
        ScoreRequest request = sampleRequest();
        when(bureauClient.fetchProfile(request.applicantId())).thenReturn(profile);
        when(repaymentHistoryClient.fetchHistory(request.applicantId())).thenReturn(history);
        when(modelScorer.score(any())).thenReturn(new ScoreOutcome(0.5, List.of()));

        scoringService().score(request);

        Map<String, Double> expected = FeatureVector.from(profile, history, request);
        verify(modelScorer).score(expected);
    }

    @Test
    void score_priorDefaultForcesDecline_evenWhenTheModelWouldApprove() {
        when(bureauClient.fetchProfile(any())).thenReturn(sampleProfile());
        when(repaymentHistoryClient.fetchHistory(any())).thenReturn(new RepaymentHistory(0, 1, 0, 1.0));
        when(modelScorer.score(any())).thenReturn(new ScoreOutcome(0.001, List.of(new ScoreFactor("age", -0.1))));

        ScoreResponse response = scoringService().score(sampleRequest());

        assertThat(response.decision()).isEqualTo(ScoreDecision.DECLINE);
        assertThat(response.scoreFactors())
                .containsExactly(new ScoreFactor("age", -0.1), new ScoreFactor("priorDefault", 3.0));
    }

    @Test
    void score_overlayAdjustsTheRiskScoreAndBand() {
        // model p = 0.2 (APPROVE); 2 late payments add +1.2 log-odds -> sigmoid(ln(0.25) + 1.2) ~= 0.4535
        when(bureauClient.fetchProfile(any())).thenReturn(sampleProfile());
        when(repaymentHistoryClient.fetchHistory(any())).thenReturn(new RepaymentHistory(0, 0, 2, 0.5));
        when(modelScorer.score(any())).thenReturn(new ScoreOutcome(0.2, List.of()));

        ScoreResponse response = scoringService().score(sampleRequest());

        assertThat(response.riskScore()).isCloseTo(0.4535, within(1e-3));
        assertThat(response.decision()).isEqualTo(ScoreDecision.MANUAL_REVIEW);
        assertThat(response.scoreFactors()).extracting(ScoreFactor::feature).containsExactly("latePayments");
    }

    @Test
    void score_handlesAModelProbabilityOfExactlyOne_withoutProducingNaN() {
        when(bureauClient.fetchProfile(any())).thenReturn(sampleProfile());
        when(repaymentHistoryClient.fetchHistory(any())).thenReturn(new RepaymentHistory(3, 0, 0, 1.0));
        when(modelScorer.score(any())).thenReturn(new ScoreOutcome(1.0, List.of()));

        ScoreResponse response = scoringService().score(sampleRequest());

        assertThat(response.riskScore()).isBetween(0.99, 1.0);
        assertThat(response.decision()).isEqualTo(ScoreDecision.DECLINE);
    }
}
