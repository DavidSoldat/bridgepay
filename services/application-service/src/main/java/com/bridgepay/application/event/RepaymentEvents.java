package com.bridgepay.application.event;

import java.math.BigDecimal;
import java.util.UUID;

/** Application Service's copy of the repayment events it consumes; must match Repayment Reconciliation's shapes. */
public final class RepaymentEvents {

    private RepaymentEvents() {
    }

    public record InstallmentPaid(UUID applicantId, UUID applicationId, UUID installmentId, int sequenceNumber,
                                  BigDecimal amount) {
    }

    public record PlanCancelled(UUID applicantId, UUID applicationId) {
    }

    public record PlanCompleted(UUID applicantId, UUID applicationId) {
    }

    public record PlanDefaulted(UUID applicantId, UUID applicationId) {
    }
}
