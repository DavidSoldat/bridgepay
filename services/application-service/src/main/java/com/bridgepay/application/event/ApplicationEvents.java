package com.bridgepay.application.event;

import java.math.BigDecimal;
import java.util.UUID;

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
