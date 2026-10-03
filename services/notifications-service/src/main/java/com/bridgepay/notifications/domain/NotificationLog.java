package com.bridgepay.notifications.domain;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
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

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, length = 500)
    private String body;

    @Column(name = "application_id")
    private UUID applicationId;

    @Column(name = "group_key", length = 64)
    private String groupKey;

    @Column(name = "sequence_number")
    private Integer sequenceNumber;

    @Column(name = "amount", precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected NotificationLog() {
        // required by JPA
    }

    public NotificationLog(UUID eventId, NotificationDraft draft) {
        this.id = UuidCreator.getTimeOrderedEpoch();
        this.eventId = eventId;
        this.applicantId = draft.applicantId();
        this.type = draft.type();
        this.title = draft.title();
        this.body = draft.body();
        this.applicationId = draft.applicationId();
        this.groupKey = draft.groupKey();
        this.sequenceNumber = draft.sequenceNumber();
        this.amount = draft.amount();
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

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public String getGroupKey() {
        return groupKey;
    }

    public Integer getSequenceNumber() {
        return sequenceNumber;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
