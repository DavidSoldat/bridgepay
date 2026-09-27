package com.bridgepay.repayment.service;

import com.bridgepay.repayment.domain.InstallmentStatus;
import com.bridgepay.repayment.domain.PlanStatus;
import com.bridgepay.repayment.dto.RepaymentHistoryResponse;
import com.bridgepay.repayment.repository.InstallmentRepository;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class RepaymentHistoryService {

    private final RepaymentPlanRepository repaymentPlanRepository;
    private final InstallmentRepository installmentRepository;

    public RepaymentHistoryService(RepaymentPlanRepository repaymentPlanRepository,
                                    InstallmentRepository installmentRepository) {
        this.repaymentPlanRepository = repaymentPlanRepository;
        this.installmentRepository = installmentRepository;
    }

    /**
     * A brand-new applicant with no plans yet isn't an error - it's the
     * common case - so this returns all-zero counts rather than 404ing;
     * Credit Risk Engine calls this on every scoring request. latePaymentCount
     * counts installments currently LATE or MISSED (i.e. not paid on time);
     * onTimeRate defaults to 1.0 (a clean slate, not a penalty) when nothing
     * has settled yet. Cancelled plans (orders whose first payment never
     * happened) are left out entirely - the shopper never owed them.
     */
    @Transactional(readOnly = true)
    public RepaymentHistoryResponse getHistory(UUID applicantId) {
        long completedPlans = repaymentPlanRepository.countByApplicantIdAndStatus(applicantId, PlanStatus.COMPLETED);
        long defaultedPlans = repaymentPlanRepository.countByApplicantIdAndStatus(applicantId, PlanStatus.DEFAULTED);
        long paid = countInstallments(applicantId, InstallmentStatus.PAID);
        long late = countInstallments(applicantId, InstallmentStatus.LATE);
        long missed = countInstallments(applicantId, InstallmentStatus.MISSED);

        long lateOrMissed = late + missed;
        long settled = paid + lateOrMissed;
        double onTimeRate = settled == 0 ? 1.0 : (double) paid / settled;

        return new RepaymentHistoryResponse((int) completedPlans, (int) defaultedPlans, (int) lateOrMissed, onTimeRate);
    }

    private long countInstallments(UUID applicantId, InstallmentStatus status) {
        return installmentRepository.countByRepaymentPlanApplicantIdAndStatusAndRepaymentPlanStatusNot(
                applicantId, status, PlanStatus.CANCELLED);
    }
}
