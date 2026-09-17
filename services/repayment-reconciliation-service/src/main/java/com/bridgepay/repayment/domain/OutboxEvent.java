package com.bridgepay.repayment.domain;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Written in the same transaction as the business data it describes. A
 * scheduled poller (see OutboxPublisher) reads unpublished rows and sends
 * them to Kafka, then marks them published - this is what guarantees an
 * event is never silently lost between a DB commit and a Kafka send.
 */
@Entity
@Table(name = "outbox_events", schema = "repayment")
public class OutboxEvent {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    @Column(name = "partition_key", nullable = false, length = 100)
    private String partitionKey;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "published", nullable = false)
    private boolean published;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OutboxEvent() {
        // required by JPA
    }

    public OutboxEvent(String topic, String partitionKey, String payload) {
        this.id = UuidCreator.getTimeOrderedEpoch();
        this.eventId = UuidCreator.getTimeOrderedEpoch();
        this.topic = topic;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.published = false;
        this.createdAt = Instant.now();
    }

    public void markPublished() {
        this.published = true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getTopic() {
        return topic;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public String getPayload() {
        return payload;
    }

    public boolean isPublished() {
        return published;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
