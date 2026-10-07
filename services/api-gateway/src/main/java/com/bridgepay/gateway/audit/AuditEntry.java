package com.bridgepay.gateway.audit;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One audited request. Append-only: nothing updates or deletes these rows. */
@Entity
@Table(name = "audit_entries")
public class AuditEntry {

    private static final int MAX_PATH = 500;

    @Id
    private UUID id;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(name = "correlation_id")
    private String correlationId;
    @Column(name = "actor_subject", nullable = false)
    private String actorSubject;
    @Column(name = "actor_username")
    private String actorUsername;
    @Column(name = "actor_role", nullable = false)
    private String actorRole;
    @Column(nullable = false)
    private String action;
    @Column(name = "target_type")
    private String targetType;
    @Column(name = "target_id")
    private String targetId;
    private String detail;
    @Column(name = "http_method", nullable = false)
    private String httpMethod;
    @Column(nullable = false)
    private String path;
    @Column(nullable = false)
    private int status;

    protected AuditEntry() {
    }

    public static AuditEntry record(AuditMatch match, String actorSubject, String actorUsername, String actorRole,
                                    String method, String path, int status, String correlationId) {
        AuditEntry e = new AuditEntry();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.occurredAt = Instant.now();
        e.correlationId = correlationId;
        e.actorSubject = actorSubject;
        e.actorUsername = actorUsername;
        e.actorRole = actorRole;
        e.action = match.action().name();
        e.targetType = match.targetType();
        e.targetId = match.targetId();
        e.detail = match.detail();
        e.httpMethod = method;
        e.path = path.length() <= MAX_PATH ? path : path.substring(0, MAX_PATH);
        e.status = status;
        return e;
    }

    public UUID getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getCorrelationId() { return correlationId; }
    public String getActorSubject() { return actorSubject; }
    public String getActorUsername() { return actorUsername; }
    public String getActorRole() { return actorRole; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getDetail() { return detail; }
    public String getHttpMethod() { return httpMethod; }
    public String getPath() { return path; }
    public int getStatus() { return status; }

    @Override
    public String toString() {
        return "AuditEntry{occurredAt=" + occurredAt + ", actor=" + actorUsername + "/" + actorSubject + " (" + actorRole
                + "), action=" + action + ", target=" + targetType + ":" + targetId + ", detail=" + detail
                + ", " + httpMethod + " " + path + " -> " + status + ", correlationId=" + correlationId + "}";
    }
}
