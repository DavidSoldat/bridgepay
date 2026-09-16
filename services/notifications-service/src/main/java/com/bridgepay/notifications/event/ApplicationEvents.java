package com.bridgepay.notifications.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Field-for-field match of the producer's payload records
 * (services/application-service/.../event/ApplicationEvents.java) - this is
 * the actual contract on the wire, not a re-derivation from the spec table.
 */
public final class ApplicationEvents {

    private ApplicationEvents() {
    }

    public record Approved(UUID applicantId, UUID merchantId, BigDecimal amount,
                            int installmentCount, BigDecimal installmentAmount) {
    }

    public record ManualReview(UUID applicantId, double riskScore) {
    }

    public record Declined(UUID applicantId, double riskScore) {
    }
}
