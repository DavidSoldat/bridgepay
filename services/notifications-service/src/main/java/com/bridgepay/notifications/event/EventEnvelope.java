package com.bridgepay.notifications.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Matches spec section 10's envelope exactly, and Application Service's own
 * EventEnvelope field-for-field (services/application-service/.../event/EventEnvelope.java)
 * - that's the producer this service actually has to read messages from.
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID aggregateId,
        int schemaVersion,
        T payload
) {
}
