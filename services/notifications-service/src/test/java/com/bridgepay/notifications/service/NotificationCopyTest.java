package com.bridgepay.notifications.service;

import com.bridgepay.notifications.domain.NotificationDraft;
import com.bridgepay.notifications.domain.NotificationType;
import com.bridgepay.notifications.event.ApplicationEvents;
import com.bridgepay.notifications.event.RepaymentEvents;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationCopyTest {

    private final UUID applicant = UUID.randomUUID();
    private final UUID application = UUID.randomUUID();

    @Test
    void approved() {
        NotificationDraft d = NotificationCopy.approved(application, new ApplicationEvents.Approved(
                applicant, UUID.randomUUID(), new BigDecimal("1069.74"), 4, new BigDecimal("267.44")));
        assertThat(d.type()).isEqualTo(NotificationType.APPLICATION_APPROVED);
        assertThat(d.title()).isEqualTo("You're approved");
        assertThat(d.body()).isEqualTo("4 payments of $267.44 for your $1,069.74 order.");
        assertThat(d.applicationId()).isEqualTo(application);
        assertThat(d.applicantId()).isEqualTo(applicant);
        assertThat(d.groupKey()).isNull();
    }

    @Test
    void manualReviewAndDeclined() {
        NotificationDraft review = NotificationCopy.manualReview(application, new ApplicationEvents.ManualReview(applicant, 0.5));
        assertThat(review.title()).isEqualTo("Your order is being reviewed");
        assertThat(review.body()).isEqualTo("We'll let you know as soon as it's decided.");
        assertThat(review.applicationId()).isEqualTo(application);

        NotificationDraft declined = NotificationCopy.declined(application, new ApplicationEvents.Declined(applicant, 0.9));
        assertThat(declined.title()).isEqualTo("Your order wasn't approved");
        assertThat(declined.body()).isEqualTo("BridgePay couldn't approve this purchase.");
    }

    @Test
    void installmentPaid_isGroupedByItsPaddleTransaction() {
        NotificationDraft d = NotificationCopy.installmentPaid(new RepaymentEvents.InstallmentPaid(
                applicant, application, UUID.randomUUID(), 2, new BigDecimal("17.44"), "txn_01abc"));
        assertThat(d.type()).isEqualTo(NotificationType.INSTALLMENT_PAID);
        assertThat(d.title()).isEqualTo("Payment 2 received");
        assertThat(d.body()).isEqualTo("$17.44");
        assertThat(d.groupKey()).isEqualTo("txn_01abc");
        assertThat(d.sequenceNumber()).isEqualTo(2);
        assertThat(d.amount()).isEqualByComparingTo("17.44");
        assertThat(d.applicationId()).isEqualTo(application);
    }

    @Test
    void installmentMissed_namesTheDueDate_andToleratesAnOldEventWithoutApplication() {
        NotificationDraft d = NotificationCopy.installmentMissed(new RepaymentEvents.InstallmentMissed(
                applicant, null, UUID.randomUUID(), 3, LocalDate.of(2026, 10, 10)));
        assertThat(d.title()).isEqualTo("Payment 3 missed");
        assertThat(d.body()).isEqualTo("We'll retry your card. It was due Oct 10, 2026.");
        assertThat(d.applicationId()).isNull();
    }

    @Test
    void planOutcomes() {
        assertThat(NotificationCopy.planCompleted(new RepaymentEvents.PlanCompleted(applicant, application)).title())
                .isEqualTo("Plan paid off");
        assertThat(NotificationCopy.planCompleted(new RepaymentEvents.PlanCompleted(applicant, application)).body())
                .isEqualTo("Thanks — you've paid everything for this order.");
        assertThat(NotificationCopy.planDefaulted(new RepaymentEvents.PlanDefaulted(applicant, application)).body())
                .isEqualTo("This plan was closed with payments outstanding.");
        NotificationDraft cancelled = NotificationCopy.planCancelled(new RepaymentEvents.PlanCancelled(applicant, application));
        assertThat(cancelled.type()).isEqualTo(NotificationType.PLAN_CANCELLED);
        assertThat(cancelled.title()).isEqualTo("Order cancelled");
        assertThat(cancelled.body()).isEqualTo("The first payment wasn't made within 24 hours.");
        assertThat(cancelled.applicationId()).isEqualTo(application);
    }
}
