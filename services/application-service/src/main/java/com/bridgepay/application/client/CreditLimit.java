package com.bridgepay.application.client;

import java.math.BigDecimal;

/** Credit Risk Engine's {@code GET /internal/credit-limit/{applicantId}} body. */
public record CreditLimit(BigDecimal limit, String band) {
}
