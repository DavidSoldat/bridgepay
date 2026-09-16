package com.bridgepay.notifications.domain;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per delivered notification, keyed by the Kafka event's eventId -
 * "send an alert" isn't naturally idempotent, so this table (plus the unique
 * constraint on event_id) is the guard against at-least-once redelivery
 * double-sending.
 */
@Entity
@Table(name = "notification_log", schema = "notifications")
public class NotificationLog {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "applicant_id", nullable = false)
    private UUID applicantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 64)
    private NotificationType type;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected NotificationLog() {
        // required by JPA
    }

    public NotificationLog(UUID eventId, UUID applicantId, NotificationType type) {
        this.id = UuidCreator.getTimeOrderedEpoch();
        this.eventId = eventId;
        this.applicantId = applicantId;
        this.type = type;
        this.sentAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getApplicantId() {
        return applicantId;
    }

    public NotificationType getType() {
        return type;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
