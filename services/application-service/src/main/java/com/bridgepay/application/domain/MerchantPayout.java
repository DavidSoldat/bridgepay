package com.bridgepay.application.domain;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchant_payouts", schema = "application")
@EntityListeners(AuditingEntityListener.class)
public class MerchantPayout {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false, updatable = false)
    private CreditApplication application;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false, updatable = false)
    private Merchant merchant;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "fee_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal feeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PayoutStatus status;

    @Column(name = "paid_at")
    private Instant paidAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MerchantPayout() {
        // required by JPA
    }

    public MerchantPayout(CreditApplication application, Merchant merchant, BigDecimal amount, BigDecimal feeAmount) {
        this.id = UuidCreator.getTimeOrderedEpoch();
        this.application = application;
        this.merchant = merchant;
        this.amount = amount;
        this.feeAmount = feeAmount;
        this.status = PayoutStatus.PAID;
        this.paidAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public CreditApplication getApplication() {
        return application;
    }

    public Merchant getMerchant() {
        return merchant;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getFeeAmount() {
        return feeAmount;
    }

    public PayoutStatus getStatus() {
        return status;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
