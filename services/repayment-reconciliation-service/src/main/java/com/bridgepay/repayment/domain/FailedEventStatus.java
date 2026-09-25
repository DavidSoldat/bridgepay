package com.bridgepay.repayment.domain;

public enum FailedEventStatus {
    FAILED,
    /** Claimed by an in-flight manual retry - see FailedEventRepository.claimForRetry. */
    RETRYING,
    RESOLVED
}
