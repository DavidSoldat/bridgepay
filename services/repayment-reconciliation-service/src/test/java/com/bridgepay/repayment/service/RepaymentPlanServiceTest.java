package com.bridgepay.repayment.service;

import com.bridgepay.repayment.client.ApplicantClient;
import com.bridgepay.repayment.client.ApplicantProfile;
import com.bridgepay.repayment.client.PaddleClient;
import com.bridgepay.repayment.client.PaddleTransactionResult;
import com.bridgepay.repayment.client.PaddleUnavailableException;
import com.bridgepay.repayment.client.PaddleWebhookData;
import com.bridgepay.repayment.domain.Installment;
import com.bridgepay.repayment.domain.RepaymentPlan;
import com.bridgepay.repayment.dto.RepaymentPlanResponse;
import com.bridgepay.repayment.event.ApplicationEvents;
import com.bridgepay.repayment.repository.InstallmentRepository;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Mock
    private PaddleWebhookService paddleWebhookService;

    private RepaymentPlanService service;

    private ApplicationEvents.Approved approvedPayload(UUID applicantId) {
        return new ApplicationEvents.Approved(applicantId, UUID.randomUUID(), new BigDecimal("200.00"),
                4, new BigDecimal("50.00"));
    }

    @Test
    void createPlan_createsAPaddleCustomer_whenApplicantHasNone() {
        service = new RepaymentPlanService(repaymentPlanRepository, installmentRepository, applicantClient, paddleClient,
                paddleWebhookService);
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
        service = new RepaymentPlanService(repaymentPlanRepository, installmentRepository, applicantClient, paddleClient,
                paddleWebhookService);
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
        service = new RepaymentPlanService(repaymentPlanRepository, installmentRepository, applicantClient, paddleClient,
                paddleWebhookService);
        UUID applicationId = UUID.randomUUID();
        UUID applicantId = UUID.randomUUID();
        when(repaymentPlanRepository.findByApplicationId(applicationId))
                .thenReturn(Optional.of(mock(RepaymentPlan.class)));

        service.createPlanFromApprovedApplication(applicationId, approvedPayload(applicantId));

        verifyNoInteractions(applicantClient, paddleClient, installmentRepository);
        verify(repaymentPlanRepository, never()).saveAndFlush(any());
    }

    @Test
    void getForApplicant_returnsThePlanWithItsInstallmentsInOrder() {
        service = new RepaymentPlanService(repaymentPlanRepository, installmentRepository, applicantClient, paddleClient,
                paddleWebhookService);
        UUID applicationId = UUID.randomUUID();
        UUID applicantId = UUID.randomUUID();
        RepaymentPlan plan = new RepaymentPlan(applicationId, applicantId, "ctm_1", "txn_1",
                new BigDecimal("200.00"), 4, new BigDecimal("50.00"));
        Installment first = new Installment(plan, 1, LocalDate.of(2026, 1, 1), new BigDecimal("50.00"));
        when(repaymentPlanRepository.findByApplicationId(applicationId)).thenReturn(Optional.of(plan));
        when(installmentRepository.findByRepaymentPlanOrderBySequenceNumberAsc(plan)).thenReturn(List.of(first));

        RepaymentPlanResponse response = service.getForApplicant(applicationId, applicantId);

        assertThat(response.applicationId()).isEqualTo(applicationId);
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.installments()).hasSize(1);
        assertThat(response.installments().get(0).sequenceNumber()).isEqualTo(1);
    }

    @Test
    void getForApplicant_throwsNoSuchElement_whenNoPlanExistsForThisApplication() {
        service = new RepaymentPlanService(repaymentPlanRepository, installmentRepository, applicantClient, paddleClient,
                paddleWebhookService);
        UUID applicationId = UUID.randomUUID();
        when(repaymentPlanRepository.findByApplicationId(applicationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getForApplicant(applicationId, UUID.randomUUID()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getForApplicant_throwsAccessDenied_whenThePlanBelongsToAnotherApplicant() {
        service = new RepaymentPlanService(repaymentPlanRepository, installmentRepository, applicantClient, paddleClient,
                paddleWebhookService);
        UUID applicationId = UUID.randomUUID();
        RepaymentPlan plan = new RepaymentPlan(applicationId, UUID.randomUUID(), "ctm_1", "txn_1",
                new BigDecimal("200.00"), 4, new BigDecimal("50.00"));
        when(repaymentPlanRepository.findByApplicationId(applicationId)).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> service.getForApplicant(applicationId, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }

    private RepaymentPlan unpaidPlan(UUID applicationId, UUID applicantId) {
        RepaymentPlan plan = new RepaymentPlan(applicationId, applicantId, "ctm_1", "txn_1",
                new BigDecimal("200.00"), 4, new BigDecimal("50.00"));
        Installment first = new Installment(plan, 1, LocalDate.of(2026, 1, 1), new BigDecimal("50.00"));
        when(repaymentPlanRepository.findByApplicationId(applicationId)).thenReturn(Optional.of(plan));
        when(installmentRepository.findByRepaymentPlanOrderBySequenceNumberAsc(plan)).thenReturn(List.of(first));
        return plan;
    }

    @Test
    void getForApplicant_appliesAFirstPaymentPaddleHasCompleted_whenTheWebhookHasNotArrived() {
        service = new RepaymentPlanService(repaymentPlanRepository, installmentRepository, applicantClient, paddleClient,
                paddleWebhookService);
        UUID applicationId = UUID.randomUUID();
        UUID applicantId = UUID.randomUUID();
        unpaidPlan(applicationId, applicantId);
        PaddleWebhookData completed = new PaddleWebhookData("txn_1", "sub_1");
        when(paddleClient.findCompletedTransaction("txn_1")).thenReturn(Optional.of(completed));

        service.getForApplicant(applicationId, applicantId);

        verify(paddleWebhookService).handle("transaction.completed", completed);
    }

    @Test
    void getForApplicant_leavesThePlanAlone_whenPaddleHasNotCompletedTheFirstPayment() {
        service = new RepaymentPlanService(repaymentPlanRepository, installmentRepository, applicantClient, paddleClient,
                paddleWebhookService);
        UUID applicationId = UUID.randomUUID();
        UUID applicantId = UUID.randomUUID();
        unpaidPlan(applicationId, applicantId);
        when(paddleClient.findCompletedTransaction("txn_1")).thenReturn(Optional.empty());

        RepaymentPlanResponse response = service.getForApplicant(applicationId, applicantId);

        assertThat(response.checkoutTransactionId()).isEqualTo("txn_1");
        verifyNoInteractions(paddleWebhookService);
    }

    @Test
    void getForOps_stillAnswers_whenPaddleIsDown() {
        service = new RepaymentPlanService(repaymentPlanRepository, installmentRepository, applicantClient, paddleClient,
                paddleWebhookService);
        UUID applicationId = UUID.randomUUID();
        unpaidPlan(applicationId, UUID.randomUUID());
        when(paddleClient.findCompletedTransaction("txn_1"))
                .thenThrow(new PaddleUnavailableException("down", new RuntimeException()));

        RepaymentPlanResponse response = service.getForOps(applicationId);

        assertThat(response.checkoutTransactionId()).isEqualTo("txn_1");
        verifyNoInteractions(paddleWebhookService);
    }

    @Test
    void getForApplicant_doesNotAskPaddle_whenTheFirstInstallmentIsAlreadyPaid() {
        service = new RepaymentPlanService(repaymentPlanRepository, installmentRepository, applicantClient, paddleClient,
                paddleWebhookService);
        UUID applicationId = UUID.randomUUID();
        UUID applicantId = UUID.randomUUID();
        RepaymentPlan plan = unpaidPlan(applicationId, applicantId);
        installmentRepository.findByRepaymentPlanOrderBySequenceNumberAsc(plan).get(0).markPaid("txn_1");

        service.getForApplicant(applicationId, applicantId);

        verifyNoInteractions(paddleClient, paddleWebhookService);
    }
}
