package com.bridgepay.repayment.repository;

import com.bridgepay.repayment.domain.PlanStatus;
import com.bridgepay.repayment.domain.RepaymentPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepaymentPlanRepository extends JpaRepository<RepaymentPlan, UUID> {

    Optional<RepaymentPlan> findByApplicationId(UUID applicationId);

    Optional<RepaymentPlan> findByPaddleSubscriptionId(String paddleSubscriptionId);

    List<RepaymentPlan> findByApplicantId(UUID applicantId);

    long countByApplicantIdAndStatus(UUID applicantId, PlanStatus status);
}
