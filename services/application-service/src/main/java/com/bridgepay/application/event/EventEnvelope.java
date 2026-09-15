package com.bridgepay.application.event;

import com.github.f4b6a3.uuid.UuidCreator;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID aggregateId,
        int schemaVersion,
        T payload
) {
    public static <T> EventEnvelope<T> of(String eventType, UUID aggregateId, T payload) {
        return new EventEnvelope<>(UuidCreator.getTimeOrderedEpoch(), eventType, Instant.now(), aggregateId, 1, payload);
    }
}
