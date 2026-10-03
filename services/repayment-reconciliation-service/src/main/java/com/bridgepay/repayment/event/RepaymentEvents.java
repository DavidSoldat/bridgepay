package com.bridgepay.repayment.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Must match Notifications Service's own copy of these payload shapes
 * (com.bridgepay.notifications.event.RepaymentEvents) field-for-field - both
 * gained paddleTransactionId and applicationId together - including
 * applicantId on all of them - that service's own stated assumption
 * about this contract, confirmed correct by this design. Application Service
 * keeps its own copy of InstallmentPaid and PlanCancelled, which must match too.
 * InstallmentPaid also carries paddleTransactionId for grouping; InstallmentMissed
 * carries applicationId for linking.
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
