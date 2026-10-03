package com.bridgepay.repayment.repository;

import com.bridgepay.repayment.domain.InstallmentStatus;
import com.bridgepay.repayment.domain.PlanStatus;
import com.bridgepay.repayment.domain.RepaymentPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepaymentPlanRepository extends JpaRepository<RepaymentPlan, UUID> {

    Optional<RepaymentPlan> findByApplicationId(UUID applicationId);

    Optional<RepaymentPlan> findByPaddleSubscriptionId(String paddleSubscriptionId);

    List<RepaymentPlan> findByApplicantId(UUID applicantId);

    long countByApplicantIdAndStatus(UUID applicantId, PlanStatus status);

    @Query("""
            select p.id from RepaymentPlan p
            where p.status = :status
              and p.createdAt < :cutoff
              and exists (select i.id from Installment i
                          where i.repaymentPlan = p and i.sequenceNumber = 1 and i.status in :unpaid)""")
    List<UUID> findIdsWithUnpaidFirstInstallmentCreatedBefore(@Param("cutoff") Instant cutoff,
                                                             @Param("status") PlanStatus status,
                                                             @Param("unpaid") List<InstallmentStatus> unpaid);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from RepaymentPlan p where p.id = :id")
    Optional<RepaymentPlan> findByIdForUpdate(@Param("id") UUID id);

    /** 1 if this call now holds the plan's early-payment claim; a claim older than 2 minutes counts as abandoned. */
    @Modifying
    @Transactional
    @Query(value = """
            update repayment.repayment_plans set early_payment_claimed_at = now()
            where id = :id
              and (early_payment_claimed_at is null or early_payment_claimed_at < now() - interval '2 minutes')""",
            nativeQuery = true)
    int claimEarlyPayment(@Param("id") UUID id);

    @Modifying
    @Transactional
    @Query(value = "update repayment.repayment_plans set early_payment_claimed_at = null where id = :id", nativeQuery = true)
    int releaseEarlyPayment(@Param("id") UUID id);
}
