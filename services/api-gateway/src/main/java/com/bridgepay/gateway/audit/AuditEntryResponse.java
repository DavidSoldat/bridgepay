package com.bridgepay.gateway.audit;

import java.time.Instant;
import java.util.UUID;

/** An audit row as ops read it; the actor's subject stays internal. */
public record AuditEntryResponse(UUID id, Instant occurredAt, String correlationId, String actorUsername,
                                 String actorRole, String action, String targetType, String targetId,
                                 String detail, String httpMethod, String path, int status) {

    public static AuditEntryResponse of(AuditEntry e) {
        return new AuditEntryResponse(e.getId(), e.getOccurredAt(), e.getCorrelationId(), e.getActorUsername(),
                e.getActorRole(), e.getAction(), e.getTargetType(), e.getTargetId(), e.getDetail(),
                e.getHttpMethod(), e.getPath(), e.getStatus());
    }
}
