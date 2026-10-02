package com.bridgepay.application.client;

import java.util.Optional;
import java.util.UUID;

public interface CreditLimitClient {

    /** Empty when the Credit Risk Engine couldn't say - never a guessed limit. */
    Optional<CreditLimit> creditLimit(UUID applicantId);
}
