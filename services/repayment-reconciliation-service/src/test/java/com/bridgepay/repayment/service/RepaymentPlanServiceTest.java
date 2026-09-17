package com.bridgepay.repayment.service;

import com.bridgepay.repayment.client.ApplicantClient;
import com.bridgepay.repayment.client.ApplicantProfile;
import com.bridgepay.repayment.client.PaddleClient;
import com.bridgepay.repayment.client.PaddleTransactionResult;
import com.bridgepay.repayment.domain.RepaymentPlan;
import com.bridgepay.repayment.event.ApplicationEvents;
import com.bridgepay.repayment.repository.InstallmentRepository;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RepaymentPlanServiceTest {

    @Mock
    private RepaymentPlanRepository repaymentPlanRepository;
    @Mock
    private InstallmentRepository installmentRepository;
    @Mock
    private ApplicantClient applicantClient;
    @Mock
    private PaddleClient paddleClient;

    private RepaymentPlanService service;

    private ApplicationEvents.Approved approvedPayload(UUID applicantId) {
        return new ApplicationEvents.Approved(applicantId, UUID.randomUUID(), new BigDecimal("200.00"),
                4, new BigDecimal("50.00"));
    }

    @Test
    void createPlan_createsAPaddleCustomer_whenApplicantHasNone() {
        service = new RepaymentPlanService(repaymentPlanRepository, installmentRepository, applicantClient, paddleClient);
        UUID applicationId = UUID.randomUUID();
        UUID applicantId = UUID.randomUUID();
        when(repaymentPlanRepository.findByApplicationId(applicationId)).thenReturn(Optional.empty());
        when(applicantClient.fetchProfile(applicantId))
                .thenReturn(new ApplicantProfile(applicantId, "Ana", "Doe", "ana@example.com", null));
        when(paddleClient.findOrCreateCustomer("ana@example.com", "Ana Doe")).thenReturn("ctm_new");
        when(paddleClient.createInstallmentTransaction(eq("ctm_new"), any()))
                .thenReturn(new PaddleTransactionResult("txn_1", "https://sandbox.paddle.com/checkout/txn_1"));
        when(repaymentPlanRepository.saveAndFlush(any(RepaymentPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createPlanFromApprovedApplication(applicationId, approvedPayload(applicantId));

        verify(applicantClient).setPaddleCustomerId(applicantId, "ctm_new");
        verify(installmentRepository, times(4)).save(any());
        ArgumentCaptor<RepaymentPlan> planCaptor = ArgumentCaptor.forClass(RepaymentPlan.class);
        verify(repaymentPlanRepository).saveAndFlush(planCaptor.capture());
        assertThat(planCaptor.getValue().getPaddleCustomerId()).isEqualTo("ctm_new");
        assertThat(planCaptor.getValue().getPaddleSubscriptionId()).isEqualTo("txn_1");
    }

    @Test
    void createPlan_reusesExistingPaddleCustomer_withoutCreatingANewOne() {
        service = new RepaymentPlanService(repaymentPlanRepository, installmentRepository, applicantClient, paddleClient);
        UUID applicationId = UUID.randomUUID();
        UUID applicantId = UUID.randomUUID();
        when(repaymentPlanRepository.findByApplicationId(applicationId)).thenReturn(Optional.empty());
        when(applicantClient.fetchProfile(applicantId))
                .thenReturn(new ApplicantProfile(applicantId, "Ana", "Doe", "ana@example.com", "ctm_existing"));
        when(paddleClient.createInstallmentTransaction(eq("ctm_existing"), any()))
                .thenReturn(new PaddleTransactionResult("txn_2", "https://sandbox.paddle.com/checkout/txn_2"));
        when(repaymentPlanRepository.saveAndFlush(any(RepaymentPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createPlanFromApprovedApplication(applicationId, approvedPayload(applicantId));

        verify(paddleClient, never()).findOrCreateCustomer(any(), any());
        verify(applicantClient, never()).setPaddleCustomerId(any(), any());
    }

    @Test
    void createPlan_skipsEntirely_whenAPlanAlreadyExistsForThisApplication() {
        service = new RepaymentPlanService(repaymentPlanRepository, installmentRepository, applicantClient, paddleClient);
        UUID applicationId = UUID.randomUUID();
        UUID applicantId = UUID.randomUUID();
        when(repaymentPlanRepository.findByApplicationId(applicationId))
                .thenReturn(Optional.of(mock(RepaymentPlan.class)));

        service.createPlanFromApprovedApplication(applicationId, approvedPayload(applicantId));

        verifyNoInteractions(applicantClient, paddleClient, installmentRepository);
        verify(repaymentPlanRepository, never()).saveAndFlush(any());
    }
}
