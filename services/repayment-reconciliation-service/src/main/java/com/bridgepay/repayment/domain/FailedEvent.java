package com.bridgepay.repayment.domain;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * A Kafka record whose listener still failed after the error handler's
 * retries were exhausted - the database-backed dead-letter store ops can
 * list and retry (see FailedEventService). payload is the raw record value,
 * replayed verbatim on retry.
 */
@Entity
@Table(name = "failed_events", schema = "repayment")
@EntityListeners(AuditingEntityListener.class)
public class FailedEvent {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    @Column(name = "message_key", length = 100)
    private String messageKey;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "error_message", nullable = false, columnDefinition = "TEXT")
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private FailedEventStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FailedEvent() {
        // required by JPA
    }

    public FailedEvent(String topic, String messageKey, String payload, String errorMessage) {
        this.id = UuidCreator.getTimeOrderedEpoch();
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.errorMessage = errorMessage;
        this.status = FailedEventStatus.FAILED;
        this.attempts = 1;
    }

    /** The most specific cause's message - listener exceptions arrive wrapped by Spring Kafka. */
    public static String describe(Throwable ex) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(ex);
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }

    public void markResolved() {
        this.status = FailedEventStatus.RESOLVED;
    }

    public void recordFailedRetry(String errorMessage) {
        this.attempts++;
        this.errorMessage = errorMessage;
    }

    public UUID getId() {
        return id;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public FailedEventStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
