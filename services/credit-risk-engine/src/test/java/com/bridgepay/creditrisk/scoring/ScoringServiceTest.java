package com.bridgepay.creditrisk.scoring;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private BureauProfile incomeOf(double monthlyIncome) {
        return new BureauProfile(0.4, 35, 0, 0.3, monthlyIncome, 5, 0, 1, 0, 0);
    }

    private void givenModel(double probability, BureauProfile profile, RepaymentHistory history) {
        when(bureauClient.fetchProfile(any())).thenReturn(profile);
        when(repaymentHistoryClient.fetchHistory(any())).thenReturn(history);
        when(modelScorer.score(any())).thenReturn(new ScoreOutcome(probability, List.of()));
    }

    private static final RepaymentHistory CLEAN = new RepaymentHistory(0, 0, 0, 1.0);

    @ParameterizedTest
    @CsvSource({
            // p,    income,  limit,   band
            "0.10,   2000,    1000.00, LOW",
            "0.29,   5000,    1500.00, LOW",      // capped
            "0.10,   1001,    500.00,  LOW",      // 500.5 floored
            "0.30,   1200,    300.00,  MEDIUM",
            "0.69,   4000,    500.00,  MEDIUM",   // capped
            "0.70,   9000,    0.00,    HIGH",
            "0.10,   0,       0.00,    LOW",      // no income, no limit
            "0.10,   -50,     0.00,    LOW",
    })
    void creditLimit_isTieredByRiskBandAndIncome(double p, double income, String limit, CreditBand band) {
        givenModel(p, incomeOf(income), CLEAN);

        CreditLimitResponse response = scoringService().creditLimit(UUID.randomUUID());

        assertThat(response.limit()).isEqualByComparingTo(limit);
        assertThat(response.limit().scale()).isEqualTo(2);
        assertThat(response.band()).isEqualTo(band);
    }

    @Test
    void creditLimit_priorDefault_isZero_evenWhenTheModelLikesTheShopper() {
        givenModel(0.01, incomeOf(9000), new RepaymentHistory(0, 1, 0, 1.0));

        CreditLimitResponse response = scoringService().creditLimit(UUID.randomUUID());

        assertThat(response.limit()).isEqualByComparingTo("0");
        assertThat(response.band()).isEqualTo(CreditBand.HIGH);
    }

    @Test
    void creditLimit_completedPlansCanLiftAShopperIntoTheLowBand() {
        // model p = 0.35 (MEDIUM); 3 completed plans = -1.2 log-odds -> sigmoid(ln(0.35/0.65) - 1.2) ~= 0.14
        givenModel(0.35, incomeOf(2000), new RepaymentHistory(3, 0, 0, 1.0));

        assertThat(scoringService().creditLimit(UUID.randomUUID()).band()).isEqualTo(CreditBand.LOW);
    }

    @Test
    void creditLimit_doesNotApplyTheAmountRule() {
        // no amount yet: income 100 would always fire amountToIncome on a real order
        givenModel(0.29, incomeOf(100), CLEAN);

        assertThat(scoringService().creditLimit(UUID.randomUUID()).band()).isEqualTo(CreditBand.LOW);
    }

    @Test
    void creditLimit_throwsWhenTheBureauIsDown_neverInventsALimit() {
        when(bureauClient.fetchProfile(any())).thenThrow(new BureauUnavailableException("down", null));

        assertThatThrownBy(() -> scoringService().creditLimit(UUID.randomUUID()))
                .isInstanceOf(BureauUnavailableException.class);
    }

    /** The limit is a promise checkout keeps: same inputs, order at the limit -> the band's decision. */
    @Test
    void lowBandShopper_checkingOutExactlyAtTheLimit_isApproved_andOneCentOverIsNot() {
        givenModel(0.2, incomeOf(2000), CLEAN);
        UUID applicant = UUID.randomUUID();
        CreditLimitResponse limit = scoringService().creditLimit(applicant);
        assertThat(limit.limit()).isEqualByComparingTo("1000.00");

        ScoreResponse atLimit = scoringService().score(
                new ScoreRequest(applicant, limit.limit(), "general", Instant.now()));
        ScoreResponse overLimit = scoringService().score(
                new ScoreRequest(applicant, limit.limit().add(new BigDecimal("0.01")), "general", Instant.now()));

        assertThat(atLimit.decision()).isEqualTo(ScoreDecision.APPROVE);
        assertThat(overLimit.decision()).isEqualTo(ScoreDecision.MANUAL_REVIEW);
        assertThat(overLimit.scoreFactors()).extracting(ScoreFactor::feature).contains("amountToIncome");
    }

    @Test
    void mediumBandShopper_checkingOutExactlyAtTheLimit_goesToManualReview() {
        givenModel(0.5, incomeOf(1200), CLEAN);
        UUID applicant = UUID.randomUUID();
        CreditLimitResponse limit = scoringService().creditLimit(applicant);
        assertThat(limit.limit()).isEqualByComparingTo("300.00");

        ScoreResponse atLimit = scoringService().score(
                new ScoreRequest(applicant, limit.limit(), "general", Instant.now()));

        assertThat(atLimit.decision()).isEqualTo(ScoreDecision.MANUAL_REVIEW);
        assertThat(atLimit.scoreFactors()).extracting(ScoreFactor::feature).doesNotContain("amountToIncome");
    }
}
