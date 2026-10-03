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
        RepaymentPlan plan = planOf(installmentCount);
        lenient().when(repaymentPlanRepository.findByIdForUpdate(plan.getId())).thenReturn(Optional.of(plan));
        return plan;
    }

    private RepaymentPlan planOf(int installmentCount) {
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

    @Test
    void transactionCompleted_redeliveredForAnAlreadyPaidTransaction_doesNotPayTheNextInstallment() {
        RepaymentPlan plan = newPlan(4);
        when(repaymentPlanRepository.findByPaddleSubscriptionId("sub_real")).thenReturn(Optional.of(plan));
        when(installmentRepository.existsByPaddleTransactionId("txn_placeholder")).thenReturn(true);

        service.handle("transaction.completed", new PaddleWebhookData("txn_placeholder", "sub_real"));

        verify(installmentRepository, never()).findFirstByRepaymentPlanAndStatusInOrderBySequenceNumberAsc(any(), any());
        verifyNoInteractions(outboxEventRepository);
    }

    private static PaddleWebhookData charge(String txn, String sub, int quantity) {
        return new PaddleWebhookData(txn, sub, List.of(new PaddleWebhookData.Item(quantity)));
    }

    @Test
    void transactionCompleted_withQuantityTwo_paysTheNextTwoInstallments_andKeepsThePlanActive() {
        RepaymentPlan plan = newPlan(4);
        Installment second = newInstallment(plan, 2);
        Installment third = newInstallment(plan, 3);
        when(repaymentPlanRepository.findByPaddleSubscriptionId("sub_1")).thenReturn(Optional.of(plan));
        when(installmentRepository.findFirstByRepaymentPlanAndStatusInOrderBySequenceNumberAsc(
                eq(plan), eq(List.of(InstallmentStatus.SCHEDULED, InstallmentStatus.LATE))))
                .thenReturn(Optional.of(second), Optional.of(third));

        service.handle("transaction.completed", charge("txn_charge", "sub_1", 2));

        assertThat(second.getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(third.getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(third.getPaddleTransactionId()).isEqualTo("txn_charge");
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.ACTIVE);
        verify(outboxEventRepository, times(2)).save(argThat(e -> e.getTopic().equals("repayments.installment-paid")));
        verify(paddleClient, never()).cancelSubscription(any());
    }

    @Test
    void transactionCompleted_payingOffTheRest_completesThePlanOnce_andCancelsTheSubscription() {
        RepaymentPlan plan = newPlan(4);
        Installment second = newInstallment(plan, 2);
        Installment third = newInstallment(plan, 3);
        Installment fourth = newInstallment(plan, 4);
        when(repaymentPlanRepository.findByPaddleSubscriptionId("sub_1")).thenReturn(Optional.of(plan));
        when(installmentRepository.findFirstByRepaymentPlanAndStatusInOrderBySequenceNumberAsc(
                eq(plan), eq(List.of(InstallmentStatus.SCHEDULED, InstallmentStatus.LATE))))
                .thenReturn(Optional.of(second), Optional.of(third), Optional.of(fourth));

        service.handle("transaction.completed", charge("txn_payoff", "sub_1", 3));

        assertThat(fourth.getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.COMPLETED);
        verify(outboxEventRepository, times(3)).save(argThat(e -> e.getTopic().equals("repayments.installment-paid")));
        verify(outboxEventRepository, times(1)).save(argThat(e -> e.getTopic().equals("repayments.plan-completed")));
        verify(paddleClient, times(1)).cancelSubscription(plan.getPaddleSubscriptionId());
    }

    @Test
    void transactionCompleted_coveringMoreThanIsLeft_paysWhatIsLeft_andCompletesOnce() {
        RepaymentPlan plan = newPlan(4);
        Installment fourth = newInstallment(plan, 4);
        when(repaymentPlanRepository.findByPaddleSubscriptionId("sub_1")).thenReturn(Optional.of(plan));
        when(installmentRepository.findFirstByRepaymentPlanAndStatusInOrderBySequenceNumberAsc(
                eq(plan), eq(List.of(InstallmentStatus.SCHEDULED, InstallmentStatus.LATE))))
                .thenReturn(Optional.of(fourth));

        service.handle("transaction.completed", charge("txn_payoff", "sub_1", 3));

        assertThat(fourth.getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.COMPLETED);
        verify(outboxEventRepository, times(1)).save(argThat(e -> e.getTopic().equals("repayments.installment-paid")));
        verify(paddleClient, times(1)).cancelSubscription(plan.getPaddleSubscriptionId());
    }

    @Test
    void installmentsCovered_isOneForARenewalWithoutItems_andTheSummedQuantityOtherwise() {
        assertThat(new PaddleWebhookData("txn_r", "sub_1").installmentsCovered()).isEqualTo(1);
        assertThat(new PaddleWebhookData("txn_r", "sub_1", null).installmentsCovered()).isEqualTo(1);
        assertThat(charge("txn_c", "sub_1", 3).installmentsCovered()).isEqualTo(3);
    }
}
