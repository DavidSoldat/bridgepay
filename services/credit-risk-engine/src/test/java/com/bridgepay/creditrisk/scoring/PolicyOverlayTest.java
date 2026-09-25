package com.bridgepay.creditrisk.scoring;

import com.bridgepay.creditrisk.client.RepaymentHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PolicyOverlayTest {

    private static final RepaymentHistory CLEAN = new RepaymentHistory(0, 0, 0, 1.0);
    private static final BigDecimal SMALL = new BigDecimal("100.00");

    @Test
    void cleanHistoryAndModestAmount_changesNothing() {
        PolicyOverlay.Result r = PolicyOverlay.apply(-1.5, CLEAN, 5000.0, SMALL);

        assertThat(r.logit()).isEqualTo(-1.5);
        assertThat(r.factors()).isEmpty();
        assertThat(r.forceDecline()).isFalse();
    }

    @Test
    void priorDefault_addsThreeAndForcesDecline() {
        PolicyOverlay.Result r = PolicyOverlay.apply(-1.5, new RepaymentHistory(0, 1, 0, 1.0), 5000.0, SMALL);

        assertThat(r.logit()).isCloseTo(1.5, within(1e-9));
        assertThat(r.factors()).containsExactly(new ScoreFactor("priorDefault", 3.0));
        assertThat(r.forceDecline()).isTrue();
    }

    @Test
    void latePayments_addPointSixEach() {
        PolicyOverlay.Result r = PolicyOverlay.apply(0.0, new RepaymentHistory(0, 0, 2, 0.5), 5000.0, SMALL);

        assertThat(r.factors()).hasSize(1);
        assertThat(r.factors().getFirst().feature()).isEqualTo("latePayments");
        assertThat(r.factors().getFirst().contribution()).isCloseTo(1.2, within(1e-9));
        assertThat(r.logit()).isCloseTo(1.2, within(1e-9));
        assertThat(r.forceDecline()).isFalse();
    }

    @Test
    void latePayments_capAtTwoPointFour() {
        PolicyOverlay.Result r = PolicyOverlay.apply(0.0, new RepaymentHistory(0, 0, 50, 0.1), 5000.0, SMALL);

        assertThat(r.factors().getFirst().contribution()).isCloseTo(2.4, within(1e-9));
    }

    @Test
    void completedPlans_subtractPointFourEach() {
        PolicyOverlay.Result r = PolicyOverlay.apply(0.0, new RepaymentHistory(2, 0, 0, 1.0), 5000.0, SMALL);

        assertThat(r.factors()).hasSize(1);
        assertThat(r.factors().getFirst().feature()).isEqualTo("completedPlans");
        assertThat(r.factors().getFirst().contribution()).isCloseTo(-0.8, within(1e-9));
    }

    @Test
    void completedPlans_capAtMinusOnePointTwo() {
        PolicyOverlay.Result r = PolicyOverlay.apply(0.0, new RepaymentHistory(40, 0, 0, 1.0), 5000.0, SMALL);

        assertThat(r.factors().getFirst().contribution()).isCloseTo(-1.2, within(1e-9));
    }

    @Test
    void amountToIncome_firesAboveHalfOfMonthlyIncome() {
        PolicyOverlay.Result r = PolicyOverlay.apply(0.0, CLEAN, 1000.0, new BigDecimal("500.01"));

        assertThat(r.factors()).containsExactly(new ScoreFactor("amountToIncome", 1.0));
        assertThat(r.logit()).isEqualTo(1.0);
    }

    @Test
    void amountToIncome_doesNotFireAtExactlyHalf() {
        PolicyOverlay.Result r = PolicyOverlay.apply(0.0, CLEAN, 1000.0, new BigDecimal("500.00"));

        assertThat(r.factors()).isEmpty();
    }

    @Test
    void amountToIncome_firesWhenIncomeIsZeroOrNegative() {
        assertThat(PolicyOverlay.apply(0.0, CLEAN, 0.0, SMALL).factors())
                .containsExactly(new ScoreFactor("amountToIncome", 1.0));
        assertThat(PolicyOverlay.apply(0.0, CLEAN, -10.0, SMALL).factors())
                .containsExactly(new ScoreFactor("amountToIncome", 1.0));
    }

    @Test
    void multipleRules_sumAndEmitInFixedOrder() {
        PolicyOverlay.Result r = PolicyOverlay.apply(0.0, new RepaymentHistory(1, 1, 1, 0.5), 100.0, SMALL);

        assertThat(r.factors()).extracting(ScoreFactor::feature)
                .containsExactly("priorDefault", "latePayments", "completedPlans", "amountToIncome");
        assertThat(r.logit()).isCloseTo(3.0 + 0.6 - 0.4 + 1.0, within(1e-9));
        assertThat(r.forceDecline()).isTrue();
    }
}
