package com.bridgepay.notifications.service;

import com.bridgepay.notifications.domain.NotificationDraft;
import com.bridgepay.notifications.event.ApplicationEvents;
import com.bridgepay.notifications.event.RepaymentEvents;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import static com.bridgepay.notifications.domain.NotificationType.*;

/** Shopper-facing wording, fixed when the event is recorded so a notification never changes after the fact. */
public final class NotificationCopy {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    private NotificationCopy() {
    }

    public static String money(BigDecimal amount) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(amount);
    }

    public static NotificationDraft approved(UUID applicationId, ApplicationEvents.Approved e) {
        return NotificationDraft.simple(e.applicantId(), APPLICATION_APPROVED, "You're approved",
                e.installmentCount() + " payments of " + money(e.installmentAmount()) + " for your "
                        + money(e.amount()) + " order.", applicationId);
    }

    public static NotificationDraft manualReview(UUID applicationId, ApplicationEvents.ManualReview e) {
        return NotificationDraft.simple(e.applicantId(), APPLICATION_MANUAL_REVIEW, "Your order is being reviewed",
                "We'll let you know as soon as it's decided.", applicationId);
    }

    public static NotificationDraft declined(UUID applicationId, ApplicationEvents.Declined e) {
        return NotificationDraft.simple(e.applicantId(), APPLICATION_DECLINED, "Your order wasn't approved",
                "BridgePay couldn't approve this purchase.", applicationId);
    }

    public static NotificationDraft installmentPaid(RepaymentEvents.InstallmentPaid e) {
        return new NotificationDraft(e.applicantId(), INSTALLMENT_PAID, "Payment " + e.sequenceNumber() + " received",
                money(e.amount()), e.applicationId(), e.paddleTransactionId(), e.sequenceNumber(), e.amount());
    }

    public static NotificationDraft installmentMissed(RepaymentEvents.InstallmentMissed e) {
        return NotificationDraft.simple(e.applicantId(), INSTALLMENT_MISSED, "Payment " + e.sequenceNumber() + " missed",
                "We'll retry your card. It was due " + DATE.format(e.dueDate()) + ".", e.applicationId());
    }

    public static NotificationDraft planCompleted(RepaymentEvents.PlanCompleted e) {
        return NotificationDraft.simple(e.applicantId(), PLAN_COMPLETED, "Plan paid off",
                "Thanks — you've paid everything for this order.", e.applicationId());
    }

    public static NotificationDraft planDefaulted(RepaymentEvents.PlanDefaulted e) {
        return NotificationDraft.simple(e.applicantId(), PLAN_DEFAULTED, "Plan defaulted",
                "This plan was closed with payments outstanding.", e.applicationId());
    }

    public static NotificationDraft planCancelled(RepaymentEvents.PlanCancelled e) {
        return NotificationDraft.simple(e.applicantId(), PLAN_CANCELLED, "Order cancelled",
                "The first payment wasn't made within 24 hours.", e.applicationId());
    }

    public static NotificationDraft planRefunded(RepaymentEvents.PlanRefunded e) {
        return e.refundedAmount().signum() > 0
                ? NotificationDraft.simple(e.applicantId(), PLAN_REFUNDED, "Refund issued",
                        money(e.refundedAmount()) + " is on its way back to your card. Your remaining payments are cancelled.",
                        e.applicationId())
                : NotificationDraft.simple(e.applicantId(), PLAN_REFUNDED, "Order cancelled",
                        "The merchant cancelled this order. You haven't been charged.", e.applicationId());
    }
}
