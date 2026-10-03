package com.bridgepay.repayment.dto;

import com.bridgepay.repayment.service.EarlyPaymentScope;
import jakarta.validation.constraints.NotNull;

public record EarlyPaymentRequest(@NotNull EarlyPaymentScope scope) {
}
