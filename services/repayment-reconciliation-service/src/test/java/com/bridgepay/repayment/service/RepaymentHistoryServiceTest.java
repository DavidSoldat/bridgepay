package com.bridgepay.repayment.service;

import com.bridgepay.repayment.domain.InstallmentStatus;
import com.bridgepay.repayment.domain.PlanStatus;
import com.bridgepay.repayment.dto.RepaymentHistoryResponse;
import com.bridgepay.repayment.repository.InstallmentRepository;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepaymentHistoryServiceTest {

    @Mock
    private RepaymentPlanRepository repaymentPlanRepository;
    @Mock
    private InstallmentRepository installmentRepository;

    @Test
    void getHistory_returnsAllZeros_forAnApplicantWithNoPlansYet() {
        RepaymentHistoryService service = new RepaymentHistoryService(repaymentPlanRepository, installmentRepository);
        UUID applicantId = UUID.randomUUID();

        RepaymentHistoryResponse response = service.getHistory(applicantId);

        assertThat(response.completedPlans()).isZero();
        assertThat(response.defaultedPlans()).isZero();
        assertThat(response.latePaymentCount()).isZero();
        assertThat(response.onTimeRate()).isEqualTo(1.0);
    }

    @Test
    void getHistory_computesOnTimeRate_fromPaidVersusLateAndMissedInstallments() {
        RepaymentHistoryService service = new RepaymentHistoryService(repaymentPlanRepository, installmentRepository);
        UUID applicantId = UUID.randomUUID();
        when(repaymentPlanRepository.countByApplicantIdAndStatus(applicantId, PlanStatus.COMPLETED)).thenReturn(2L);
        when(repaymentPlanRepository.countByApplicantIdAndStatus(applicantId, PlanStatus.DEFAULTED)).thenReturn(1L);
        when(installmentRepository.countByRepaymentPlanApplicantIdAndStatusAndRepaymentPlanStatusNot(applicantId, InstallmentStatus.PAID, PlanStatus.CANCELLED)).thenReturn(6L);
        when(installmentRepository.countByRepaymentPlanApplicantIdAndStatusAndRepaymentPlanStatusNot(applicantId, InstallmentStatus.LATE, PlanStatus.CANCELLED)).thenReturn(1L);
        when(installmentRepository.countByRepaymentPlanApplicantIdAndStatusAndRepaymentPlanStatusNot(applicantId, InstallmentStatus.MISSED, PlanStatus.CANCELLED)).thenReturn(1L);

        RepaymentHistoryResponse response = service.getHistory(applicantId);

        assertThat(response.completedPlans()).isEqualTo(2);
        assertThat(response.defaultedPlans()).isEqualTo(1);
        assertThat(response.latePaymentCount()).isEqualTo(2);
        assertThat(response.onTimeRate()).isEqualTo(0.75);
    }
}
