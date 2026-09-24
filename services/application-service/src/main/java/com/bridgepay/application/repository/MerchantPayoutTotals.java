package com.bridgepay.application.repository;

import java.math.BigDecimal;

/** Both sums are null when the merchant has no payouts yet. */
public record MerchantPayoutTotals(BigDecimal gross, BigDecimal fees) {
}
