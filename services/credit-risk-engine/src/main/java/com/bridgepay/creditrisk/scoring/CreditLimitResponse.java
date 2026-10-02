package com.bridgepay.creditrisk.scoring;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Body of {@code GET /internal/credit-limit/{applicantId}}. The band is the decision the shopper's
 * score maps to with no order amount, so an order within a LOW limit (at most half of monthly income)
 * never fires amountToIncome and is scored APPROVE at checkout - the limit is a promise scoring keeps.
 */
public record CreditLimitResponse(BigDecimal limit, CreditBand band) implements Serializable {

    private static final BigDecimal LOW_CAP = new BigDecimal("1500");
    private static final BigDecimal MEDIUM_CAP = new BigDecimal("500");

    static CreditLimitResponse of(ScoreDecision decision, double monthlyIncome) {
        return switch (decision) {
            case APPROVE -> new CreditLimitResponse(share(monthlyIncome / 2, LOW_CAP), CreditBand.LOW);
            case MANUAL_REVIEW -> new CreditLimitResponse(share(monthlyIncome / 4, MEDIUM_CAP), CreditBand.MEDIUM);
            case DECLINE -> new CreditLimitResponse(BigDecimal.ZERO.setScale(2), CreditBand.HIGH);
        };
    }

    private static BigDecimal share(double amount, BigDecimal cap) {
        if (!(amount > 0)) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(amount).setScale(0, RoundingMode.FLOOR).min(cap).setScale(2);
    }
}
