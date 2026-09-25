package com.bridgepay.repayment.dto;

import com.bridgepay.repayment.domain.FailedEvent;
import com.bridgepay.repayment.domain.FailedEventStatus;

import java.time.Instant;
import java.util.UUID;

/** Deliberately omits payload - it can be large and the ops page doesn't show it. */
public record FailedEventResponse(
        UUID id,
        String topic,
        String messageKey,
        String errorMessage,
        FailedEventStatus status,
        int attempts,
        Instant createdAt,
        Instant updatedAt
) {
    public static FailedEventResponse from(FailedEvent event) {
        return new FailedEventResponse(event.getId(), event.getTopic(), event.getMessageKey(),
                event.getErrorMessage(), event.getStatus(), event.getAttempts(),
                event.getCreatedAt(), event.getUpdatedAt());
    }
}
