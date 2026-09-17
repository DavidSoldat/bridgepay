package com.bridgepay.repayment.domain;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "repayment_plans", schema = "repayment")
@EntityListeners(AuditingEntityListener.class)
public class RepaymentPlan {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "application_id", nullable = false, unique = true)
    private UUID applicationId;

    @Column(name = "applicant_id", nullable = false)
    private UUID applicantId;

    @Column(name = "paddle_customer_id", nullable = false, length = 64)
    private String paddleCustomerId;

    /**
     * Holds the initial transaction id as a placeholder until the first
     * transaction.completed webhook adopts the real subscription id Paddle
     * creates as a side effect of that transaction completing.
     */
    @Column(name = "paddle_subscription_id", length = 64)
    private String paddleSubscriptionId;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "installment_count", nullable = false)
    private int installmentCount;

    @Column(name = "installment_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal installmentAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PlanStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RepaymentPlan() {
    }

    public RepaymentPlan(UUID applicationId, UUID applicantId, String paddleCustomerId,
                          String initialPaddleTransactionId, BigDecimal totalAmount,
                          int installmentCount, BigDecimal installmentAmount) {
        this.id = UuidCreator.getTimeOrderedEpoch();
        this.applicationId = applicationId;
        this.applicantId = applicantId;
        this.paddleCustomerId = paddleCustomerId;
        this.paddleSubscriptionId = initialPaddleTransactionId;
        this.totalAmount = totalAmount;
        this.installmentCount = installmentCount;
        this.installmentAmount = installmentAmount;
        this.status = PlanStatus.ACTIVE;
    }

    public void adoptSubscriptionId(String subscriptionId) {
        this.paddleSubscriptionId = subscriptionId;
    }

    public void markCompleted() {
        this.status = PlanStatus.COMPLETED;
    }

    public void markDefaulted() {
        this.status = PlanStatus.DEFAULTED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public UUID getApplicantId() {
        return applicantId;
    }

    public String getPaddleCustomerId() {
        return paddleCustomerId;
    }

    public String getPaddleSubscriptionId() {
        return paddleSubscriptionId;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public int getInstallmentCount() {
        return installmentCount;
    }

    public BigDecimal getInstallmentAmount() {
        return installmentAmount;
    }

    public PlanStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
