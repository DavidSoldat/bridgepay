package com.bridgepay.application.domain;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchants", schema = "application")
@EntityListeners(AuditingEntityListener.class)
public class Merchant {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "fee_rate_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal feeRatePct;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Merchant() {
        // required by JPA
    }

    public Merchant(String name, BigDecimal feeRatePct) {
        this.id = UuidCreator.getTimeOrderedEpoch();
        this.name = name;
        this.feeRatePct = feeRatePct;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getFeeRatePct() {
        return feeRatePct;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
