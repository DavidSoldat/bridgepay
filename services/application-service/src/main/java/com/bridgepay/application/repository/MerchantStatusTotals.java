package com.bridgepay.application.repository;

import com.bridgepay.application.domain.ApplicationStatus;

import java.math.BigDecimal;

public record MerchantStatusTotals(ApplicationStatus status, Long count, BigDecimal volume) {
}
