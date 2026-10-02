package com.bridgepay.application.dto;

import java.math.BigDecimal;

/** {@code limit}, {@code available} and {@code band} are null when the Credit Risk Engine couldn't be asked. */
public record CreditLimitResponse(BigDecimal limit, BigDecimal outstanding, BigDecimal available, String band) {
}
