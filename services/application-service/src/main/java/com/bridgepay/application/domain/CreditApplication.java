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
import jakarta.persistence.Version;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Named CreditApplication (not Application) to avoid clashing with the everyday
 * meaning of "application" in a Spring context. Maps to the `applications` table
 * per the spec - the class name is a code-readability choice only.
 */
@Entity
@Table(name = "applications", schema = "application")
@EntityListeners(AuditingEntityListener.class)
public class CreditApplication {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // Plain UUID reference into the Applicant service's own schema - no DB-level
    // foreign key across schemas, per the cross-schema rule in the spec.
    @Column(name = "applicant_id", nullable = false, updatable = false)
    private UUID applicantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false, updatable = false)
    private Merchant merchant;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ApplicationStatus status;

    @Column(name = "risk_score")
    private Double riskScore;

    // Stored as plain JSON text rather than a native jsonb column for now -
    // avoids depending on Hibernate's JSON type-mapping behavior, which hasn't
    // been verified against this exact Boot 4.1.1 / Hibernate pairing. Trivial
    // to upgrade to a real jsonb column + @JdbcTypeCode once that's confirmed.
    @Column(name = "score_factors", columnDefinition = "TEXT")
    private String scoreFactorsJson;

    @Column(name = "decision_at")
    private Instant decisionAt;

    @Column(name = "installment_count")
    private Integer installmentCount;

    @Column(name = "installment_amount", precision = 12, scale = 2)
    private BigDecimal installmentAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_source", length = 8)
    private DecisionSource decisionSource;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "reviewer_note", length = 1000)
    private String reviewerNote;

    /** Set only by the db/demo seed; the app never writes it. */
    @Column(name = "is_demo", nullable = false, insertable = false, updatable = false)
    private boolean demo;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreditApplication() {
        // required by JPA
    }

    public CreditApplication(UUID applicantId, Merchant merchant, BigDecimal amount) {
        this.id = UuidCreator.getTimeOrderedEpoch();
        this.applicantId = applicantId;
        this.merchant = merchant;
        this.amount = amount;
        this.status = ApplicationStatus.PENDING;
    }

    public void applyDecision(ApplicationStatus decision, Double riskScore, String scoreFactorsJson,
                               Integer installmentCount, BigDecimal installmentAmount) {
        this.status = decision;
        this.riskScore = riskScore;
        this.scoreFactorsJson = scoreFactorsJson;
        this.installmentCount = installmentCount;
        this.installmentAmount = installmentAmount;
        this.decisionAt = Instant.now();
    }

    /** An approved order whose first installment was never paid. */
    public void cancel() {
        if (status == ApplicationStatus.APPROVED) {
            status = ApplicationStatus.CANCELLED;
        }
    }

    public void overrideDecision(ApplicationStatus decision) {
        this.status = decision;
        this.decisionAt = Instant.now();
    }

    public void recordDecisionMaker(DecisionSource source, String decidedBy, String reviewerNote) {
        this.decisionSource = source;
        this.decidedBy = decidedBy;
        this.reviewerNote = reviewerNote;
    }

    public UUID getId() {
        return id;
    }

    public UUID getApplicantId() {
        return applicantId;
    }

    public Merchant getMerchant() {
        return merchant;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public Double getRiskScore() {
        return riskScore;
    }

    public String getScoreFactorsJson() {
        return scoreFactorsJson;
    }

    public Instant getDecisionAt() {
        return decisionAt;
    }

    public Integer getInstallmentCount() {
        return installmentCount;
    }

    public BigDecimal getInstallmentAmount() {
        return installmentAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public DecisionSource getDecisionSource() {
        return decisionSource;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public String getReviewerNote() {
        return reviewerNote;
    }
}
