package com.bridgepay.repayment.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Local copy of Application Service's published contract for the one topic
 * this service consumes - no shared library exists in this project, so every
 * consumer hand-rolls its own copy (Notifications Service does the same).
 */
public final class ApplicationEvents {

    private ApplicationEvents() {
    }

    public record Approved(UUID applicantId, UUID merchantId, BigDecimal amount,
                            int installmentCount, BigDecimal installmentAmount) {
    }

    public record RefundRequested(UUID applicationId, UUID merchantId, UUID applicantId) {
    }
}
