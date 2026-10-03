package com.bridgepay.notifications.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Repayment Reconciliation Service doesn't exist yet, so this is this
 * service's own hand-rolled contract for those 4 topics, per spec section 10's
 * "payload highlights" - including applicantId, which the spec's highlights
 * column doesn't list but notification_log requires (see design doc's stated
 * assumption). Whoever builds Repayment Reconciliation Service must emit
 * applicantId on these 4 events for this to work. Fields added later (the
 * feed's paddleTransactionId and applicationId) are nullable for events
 * produced before they existed.
 */
public final class RepaymentEvents {

    private RepaymentEvents() {
    }

    public record InstallmentPaid(UUID applicantId, UUID applicationId, UUID installmentId, int sequenceNumber,
                                  BigDecimal amount, String paddleTransactionId) {
    }

    public record InstallmentMissed(UUID applicantId, UUID applicationId, UUID installmentId, int sequenceNumber,
                                    LocalDate dueDate) {
    }

    public record PlanCompleted(UUID applicantId, UUID applicationId) {
    }

    public record PlanDefaulted(UUID applicantId, UUID applicationId) {
    }

    public record PlanCancelled(UUID applicantId, UUID applicationId) {
    }
}
