package com.bridgepay.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class OverLimitException extends RuntimeException {

    public OverLimitException(BigDecimal amount, BigDecimal available) {
        super("This order is $%s; you have $%s available.".formatted(
                amount.setScale(2, RoundingMode.HALF_UP).toPlainString(), available.toPlainString()));
    }
}
