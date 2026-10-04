package com.bridgepay.repayment.domain;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "installments", schema = "repayment")
@EntityListeners(AuditingEntityListener.class)
public class Installment {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repayment_plan_id", nullable = false)
    private RepaymentPlan repaymentPlan;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private InstallmentStatus status;

    @Column(name = "paddle_transaction_id", length = 64)
    private String paddleTransactionId;

    @Column(name = "paid_at")
    private Instant paidAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Installment() {
    }

    public Installment(RepaymentPlan repaymentPlan, int sequenceNumber, LocalDate dueDate, BigDecimal amount) {
        this.id = UuidCreator.getTimeOrderedEpoch();
        this.repaymentPlan = repaymentPlan;
        this.sequenceNumber = sequenceNumber;
        this.dueDate = dueDate;
        this.amount = amount;
        this.status = InstallmentStatus.SCHEDULED;
    }

    public void markPaid(String paddleTransactionId) {
        this.status = InstallmentStatus.PAID;
        this.paddleTransactionId = paddleTransactionId;
        this.paidAt = Instant.now();
    }

    /** Paddle adjustment that refunded this installment's transaction; shared by installments paid together. */
    @Column(name = "refund_adjustment_id", length = 64)
    private String refundAdjustmentId;

    public void markRefunded(String adjustmentId) {
        this.status = InstallmentStatus.REFUNDED;
        this.refundAdjustmentId = adjustmentId;
    }

    public void markCancelled() {
        this.status = InstallmentStatus.CANCELLED;
    }

    public String getRefundAdjustmentId() {
        return refundAdjustmentId;
    }

    public void markLate() {
        this.status = InstallmentStatus.LATE;
    }

    public void moveDueDateEarlier(int weeks) {
        this.dueDate = dueDate.minusWeeks(weeks);
    }

    public void markMissed() {
        this.status = InstallmentStatus.MISSED;
    }

    public UUID getId() {
        return id;
    }

    public RepaymentPlan getRepaymentPlan() {
        return repaymentPlan;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public InstallmentStatus getStatus() {
        return status;
    }

    public String getPaddleTransactionId() {
        return paddleTransactionId;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
