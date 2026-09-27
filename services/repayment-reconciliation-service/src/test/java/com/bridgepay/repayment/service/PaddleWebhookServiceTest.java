package com.bridgepay.repayment.service;

import com.bridgepay.repayment.client.PaddleClient;
import com.bridgepay.repayment.client.PaddleWebhookData;
import com.bridgepay.repayment.domain.Installment;
import com.bridgepay.repayment.domain.OutboxEvent;
import com.bridgepay.repayment.domain.InstallmentStatus;
import com.bridgepay.repayment.domain.PlanStatus;
import com.bridgepay.repayment.domain.RepaymentPlan;
import com.bridgepay.repayment.repository.InstallmentRepository;
import com.bridgepay.repayment.repository.OutboxEventRepository;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaddleWebhookServiceTest {

    @Mock
    private RepaymentPlanRepository repaymentPlanRepository;
    @Mock
    private InstallmentRepository installmentRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private PaddleClient paddleClient;

    private PaddleWebhookService service;

    @BeforeEach
    void setUp() {
        service = new PaddleWebhookService(repaymentPlanRepository, installmentRepository, outboxEventRepository,
                paddleClient, new ObjectMapper());
    }

    private RepaymentPlan newPlan(int installmentCount) {
        return new RepaymentPlan(UUID.randomUUID(), UUID.randomUUID(), "ctm_1", "txn_placeholder",
                new BigDecimal("200.00"), installmentCount, new BigDecimal("50.00"));
    }

    private Installment newInstallment(RepaymentPlan plan, int sequence) {
        return new Installment(plan, sequence, LocalDate.now().plusWeeks(sequence - 1L), plan.getInstallmentAmount());
    }

    @Test
    void transactionCompleted_marksInstallmentPaid_andAdoptsSubscriptionId() {
        RepaymentPlan plan = newPlan(4);
        Installment first = newInstallment(plan, 1);
        when(repaymentPlanRepository.findByPaddleSubscriptionId("sub_real")).thenReturn(Optional.empty());
        when(repaymentPlanRepository.findByPaddleSubscriptionId("txn_placeholder")).thenReturn(Optional.of(plan));
        when(installmentRepository.findFirstByRepaymentPlanAndStatusInOrderBySequenceNumberAsc(
                eq(plan), eq(List.of(InstallmentStatus.SCHEDULED, InstallmentStatus.LATE))))
                .thenReturn(Optional.of(first));

        service.handle("transaction.completed", new PaddleWebhookData("txn_placeholder", "sub_real"));

        assertThat(plan.getPaddleSubscriptionId()).isEqualTo("sub_real");
        assertThat(first.getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.ACTIVE);
        verify(outboxEventRepository).save(argThat(e -> e.getTopic().equals("repayments.installment-paid")));
        verify(paddleClient, never()).cancelSubscription(any());
    }

    @Test
    void transactionCompleted_installmentPaidEventCarriesTheApplicationId() {
        RepaymentPlan plan = newPlan(4);
        Installment first = newInstallment(plan, 1);
        when(repaymentPlanRepository.findByPaddleSubscriptionId("sub_real")).thenReturn(Optional.empty());
        when(repaymentPlanRepository.findByPaddleSubscriptionId("txn_placeholder")).thenReturn(Optional.of(plan));
        when(installmentRepository.findFirstByRepaymentPlanAndStatusInOrderBySequenceNumberAsc(
                eq(plan), eq(List.of(InstallmentStatus.SCHEDULED, InstallmentStatus.LATE))))
                .thenReturn(Optional.of(first));

        service.handle("transaction.completed", new PaddleWebhookData("txn_placeholder", "sub_real"));

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(saved.capture());
        assertThat(saved.getValue().getPayload()).contains("\"applicationId\":\"" + plan.getApplicationId() + "\"");
    }

    @Test
    void transactionCompleted_onFinalInstallment_completesPlanAndCancelsSubscription() {
        RepaymentPlan plan = newPlan(1);
        Installment only = newInstallment(plan, 1);
        when(repaymentPlanRepository.findByPaddleSubscriptionId("sub_1")).thenReturn(Optional.of(plan));
        when(installmentRepository.findFirstByRepaymentPlanAndStatusInOrderBySequenceNumberAsc(
                eq(plan), eq(List.of(InstallmentStatus.SCHEDULED, InstallmentStatus.LATE))))
                .thenReturn(Optional.of(only));

        service.handle("transaction.completed", new PaddleWebhookData("txn_x", "sub_1"));

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.COMPLETED);
        verify(paddleClient).cancelSubscription(plan.getPaddleSubscriptionId());
        verify(outboxEventRepository).save(argThat(e -> e.getTopic().equals("repayments.installment-paid")));
        verify(outboxEventRepository).save(argThat(e -> e.getTopic().equals("repayments.plan-completed")));
    }

    @Test
    void transactionPaymentFailed_marksTheNextScheduledInstallmentLate() {
        RepaymentPlan plan = newPlan(4);
        Installment first = newInstallment(plan, 1);
        when(repaymentPlanRepository.findByPaddleSubscriptionId("sub_1")).thenReturn(Optional.of(plan));
        when(installmentRepository.findFirstByRepaymentPlanAndStatusInOrderBySequenceNumberAsc(
                eq(plan), eq(List.of(InstallmentStatus.SCHEDULED))))
                .thenReturn(Optional.of(first));

        service.handle("transaction.payment_failed", new PaddleWebhookData("txn_x", "sub_1"));

        assertThat(first.getStatus()).isEqualTo(InstallmentStatus.LATE);
        verify(outboxEventRepository).save(argThat(e -> e.getTopic().equals("repayments.installment-missed")));
    }

    @Test
    void subscriptionCanceled_defaultsAnActivePlan() {
        RepaymentPlan plan = newPlan(4);
        Installment first = newInstallment(plan, 1);
        when(repaymentPlanRepository.findByPaddleSubscriptionId("sub_1")).thenReturn(Optional.of(plan));
        when(installmentRepository.findFirstByRepaymentPlanAndStatusInOrderBySequenceNumberAsc(
                eq(plan), eq(List.of(InstallmentStatus.SCHEDULED, InstallmentStatus.LATE))))
                .thenReturn(Optional.of(first));

        service.handle("subscription.canceled", new PaddleWebhookData("sub_1", null));

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DEFAULTED);
        assertThat(first.getStatus()).isEqualTo(InstallmentStatus.MISSED);
        verify(outboxEventRepository).save(argThat(e -> e.getTopic().equals("repayments.plan-defaulted")));
    }

    @Test
    void subscriptionCanceled_isANoOp_whenWeAlreadyCompletedThePlanOurselves() {
        RepaymentPlan plan = newPlan(1);
        plan.markCompleted();
        when(repaymentPlanRepository.findByPaddleSubscriptionId("sub_1")).thenReturn(Optional.of(plan));

        service.handle("subscription.canceled", new PaddleWebhookData("sub_1", null));

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.COMPLETED);
        verifyNoInteractions(outboxEventRepository);
    }
}
