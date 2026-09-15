package com.bridgepay.application.domain;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys", schema = "application")
public class IdempotencyKey {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "applicant_id", nullable = false)
    private UUID applicantId;

    // The cached response body, replayed verbatim if this key is reused.
    @Column(name = "response_snapshot", nullable = false, columnDefinition = "TEXT")
    private String responseSnapshot;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyKey() {
        // required by JPA
    }

    public IdempotencyKey(String idempotencyKey, UUID applicantId, String responseSnapshot) {
        this.id = UuidCreator.getTimeOrderedEpoch();
        this.idempotencyKey = idempotencyKey;
        this.applicantId = applicantId;
        this.responseSnapshot = responseSnapshot;
        this.createdAt = Instant.now();
        this.expiresAt = this.createdAt.plusSeconds(24 * 60 * 60);
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getApplicantId() {
        return applicantId;
    }

    public String getResponseSnapshot() {
        return responseSnapshot;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
