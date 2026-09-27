package com.bridgepay.repayment.repository;

import com.bridgepay.repayment.domain.InstallmentStatus;
import com.bridgepay.repayment.domain.PlanStatus;
import com.bridgepay.repayment.domain.RepaymentPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
