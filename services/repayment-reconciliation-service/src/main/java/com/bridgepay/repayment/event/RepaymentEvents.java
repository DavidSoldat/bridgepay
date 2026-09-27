package com.bridgepay.repayment.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Must match Notifications Service's own copy of these same 4 payload
 * shapes (com.bridgepay.notifications.event.RepaymentEvents) byte-for-byte,
 * including applicantId on all four - that service's own stated assumption
 * about this contract, confirmed correct by this design. Application Service
 * keeps its own copy of InstallmentPaid and PlanCancelled, which must match too.
 */
public final class RepaymentEvents {

    private RepaymentEvents() {
    }

    public record InstallmentPaid(UUID applicantId, UUID applicationId, UUID installmentId, int sequenceNumber,
                                  BigDecimal amount) {
    }

    public record InstallmentMissed(UUID applicantId, UUID installmentId, int sequenceNumber, LocalDate dueDate) {
    }

    public record PlanCompleted(UUID applicantId, UUID applicationId) {
    }

    public record PlanDefaulted(UUID applicantId, UUID applicationId) {
    }

    public record PlanCancelled(UUID applicantId, UUID applicationId) {
    }
}
