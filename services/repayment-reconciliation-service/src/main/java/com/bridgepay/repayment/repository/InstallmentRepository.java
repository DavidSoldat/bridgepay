package com.bridgepay.repayment.repository;

import com.bridgepay.repayment.domain.Installment;
import com.bridgepay.repayment.domain.InstallmentStatus;
import com.bridgepay.repayment.domain.RepaymentPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InstallmentRepository extends JpaRepository<Installment, UUID> {

    List<Installment> findByRepaymentPlanOrderBySequenceNumberAsc(RepaymentPlan repaymentPlan);

    Optional<Installment> findFirstByRepaymentPlanAndStatusInOrderBySequenceNumberAsc(
            RepaymentPlan repaymentPlan, List<InstallmentStatus> statuses);

    long countByRepaymentPlanApplicantIdAndStatus(UUID applicantId, InstallmentStatus status);
}
