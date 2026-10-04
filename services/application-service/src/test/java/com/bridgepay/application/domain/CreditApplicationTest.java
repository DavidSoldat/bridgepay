package com.bridgepay.application.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreditApplicationTest {

    private static final Merchant MERCHANT = new Merchant("M", new BigDecimal("2.90"));

    private static CreditApplication application(String amount, ApplicationStatus status) {
        CreditApplication app = new CreditApplication(UUID.randomUUID(), MERCHANT, new BigDecimal(amount));
        boolean approved = status == ApplicationStatus.APPROVED;
        BigDecimal installment = approved
                ? new BigDecimal(amount).divide(BigDecimal.valueOf(4), 2, RoundingMode.HALF_UP)
                : null;
        app.applyDecision(status, 0.2, "[]", approved ? 4 : null, installment);
        return app;
    }

    @Test
    void inReview_countsTheWholeAmount() {
        assertThat(application("80.00", ApplicationStatus.MANUAL_REVIEW).outstanding()).isEqualByComparingTo("80.00");
    }

    @Test
    void approved_countsWhatIsStillToBePaid() {
        CreditApplication app = application("100.00", ApplicationStatus.APPROVED);
        assertThat(app.outstanding()).isEqualByComparingTo("100.00");

        app.recordInstallmentPaid(1);
        assertThat(app.outstanding()).isEqualByComparingTo("75.00");
    }

    @Test
    void installmentsPaid_neverGoesBackwards_andRepeatsChangeNothing() {
        CreditApplication app = application("100.00", ApplicationStatus.APPROVED);
        app.recordInstallmentPaid(2);
        app.recordInstallmentPaid(2);
        app.recordInstallmentPaid(1);

        assertThat(app.getInstallmentsPaid()).isEqualTo(2);
        assertThat(app.outstanding()).isEqualByComparingTo("50.00");
    }

    @Test
    void outstanding_neverGoesNegative_afterAllInstallmentsWithRoundingUp() {
        // 52.42 / 4 = 13.105 -> 13.11; 4 x 13.11 = 52.44 > 52.42
        CreditApplication app = application("52.42", ApplicationStatus.APPROVED);
        app.recordInstallmentPaid(4);

        assertThat(app.outstanding()).isEqualByComparingTo("0.00");
    }

    @Test
    void finishedOrRefusedOrders_countNothing() {
        for (ApplicationStatus status : new ApplicationStatus[]{ApplicationStatus.DECLINED, ApplicationStatus.PENDING}) {
            assertThat(application("80.00", status).outstanding()).isEqualByComparingTo("0.00");
        }
        CreditApplication completed = application("80.00", ApplicationStatus.APPROVED);
        completed.complete();
        CreditApplication defaulted = application("80.00", ApplicationStatus.APPROVED);
        defaulted.markDefaulted();
        CreditApplication cancelled = application("80.00", ApplicationStatus.APPROVED);
        cancelled.cancel();

        assertThat(completed.outstanding()).isEqualByComparingTo("0.00");
        assertThat(defaulted.outstanding()).isEqualByComparingTo("0.00");
        assertThat(cancelled.outstanding()).isEqualByComparingTo("0.00");
    }

    @Test
    void completeAndDefault_onlyMoveAnApprovedOrder() {
        CreditApplication approved = application("80.00", ApplicationStatus.APPROVED);
        assertThat(approved.complete()).isTrue();
        assertThat(approved.getStatus()).isEqualTo(ApplicationStatus.COMPLETED);
        assertThat(approved.markDefaulted()).isFalse();
        assertThat(approved.getStatus()).isEqualTo(ApplicationStatus.COMPLETED);

        CreditApplication declined = application("80.00", ApplicationStatus.DECLINED);
        assertThat(declined.complete()).isFalse();
        assertThat(declined.markDefaulted()).isFalse();
        assertThat(declined.getStatus()).isEqualTo(ApplicationStatus.DECLINED);
    }

    @Test
    void anApprovedOrCompletedOrderCanBeRefunded_onlyOnce() {
        CreditApplication approved = application("100.00", ApplicationStatus.APPROVED);
        approved.requestRefund();
        assertThat(approved.getStatus()).isEqualTo(ApplicationStatus.REFUND_PENDING);
        assertThatThrownBy(approved::requestRefund).isInstanceOf(IllegalStateException.class);

        CreditApplication completed = application("100.00", ApplicationStatus.APPROVED);
        completed.complete();
        completed.requestRefund();
        assertThat(completed.getStatus()).isEqualTo(ApplicationStatus.REFUND_PENDING);
    }

    @Test
    void anOrderInReviewCannotBeRefunded() {
        CreditApplication inReview = application("100.00", ApplicationStatus.MANUAL_REVIEW);
        assertThatThrownBy(inReview::requestRefund).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPendingRefundStillHoldsTheSpendingLimit_aFinishedOneDoesNot() {
        CreditApplication app = application("100.00", ApplicationStatus.APPROVED);
        app.recordInstallmentPaid(1);
        app.requestRefund();
        assertThat(app.outstanding()).isEqualByComparingTo("75.00");

        app.markRefunded();
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.REFUNDED);
        assertThat(app.outstanding()).isEqualByComparingTo("0.00");
    }
}
