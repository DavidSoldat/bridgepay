package com.bridgepay.repayment.service;

import com.bridgepay.repayment.client.PaddleClient;
import com.bridgepay.repayment.client.PaddleWebhookData;
import com.bridgepay.repayment.domain.Installment;
import com.bridgepay.repayment.domain.InstallmentStatus;
import com.bridgepay.repayment.domain.OutboxEvent;
import com.bridgepay.repayment.domain.PlanStatus;
import com.bridgepay.repayment.domain.RepaymentPlan;
import com.bridgepay.repayment.event.EventEnvelope;
import com.bridgepay.repayment.event.RepaymentEvents;
import com.bridgepay.repayment.repository.InstallmentRepository;
import com.bridgepay.repayment.repository.OutboxEventRepository;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A merchant's full refund: Paddle gives back every paid installment, the rest is cancelled. Runs under the
 * plan row lock, so webhooks, reconcile-on-read, pay-early and the expiry job can't interleave. Paddle calls
 * come before the commit; refunds are idempotent per transaction (PaddleClient#refundTransaction) and the
 * subscription is cancelled last, so a retry after a failure redoes nothing that already happened.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    private static final String TOPIC = "repayments.plan-refunded";

    private final RepaymentPlanRepository planRepository;
    private final InstallmentRepository installmentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PaddleClient paddleClient;
    private final PaddleWebhookService webhookService;
    private final ObjectMapper objectMapper;

    public RefundService(RepaymentPlanRepository planRepository, InstallmentRepository installmentRepository,
                         OutboxEventRepository outboxEventRepository, PaddleClient paddleClient,
                         PaddleWebhookService webhookService, ObjectMapper objectMapper) {
        this.planRepository = planRepository;
        this.installmentRepository = installmentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.paddleClient = paddleClient;
        this.webhookService = webhookService;
        this.objectMapper = objectMapper;
    }

    // ponytail: if Paddle cancels the subscription but our commit then fails, Paddle's subscription.canceled
    // webhook defaults the still-ACTIVE plan and the retried refund is rejected; ops sees it in failed events.
    @Transactional
    public void refund(UUID applicationId) {
        UUID planId = planRepository.findIdByApplicationId(applicationId)
                .orElseThrow(() -> new IllegalStateException("No repayment plan yet for application " + applicationId));
        RepaymentPlan plan = planRepository.findByIdForUpdate(planId).orElseThrow();

        if (plan.getStatus() == PlanStatus.REFUNDED) {
            // The original plan-refunded event committed atomically with REFUNDED; republishing would only
            // send the shopper a second notification on Kafka redelivery.
            log.debug("Plan {} already refunded, nothing to do", plan.getId());
            return;
        }
        if (plan.getStatus() == PlanStatus.CANCELLED) {
            // Unpaid-order expiry won the race: nothing was ever charged, just let application-service finish.
            publish(plan, BigDecimal.ZERO);
            return;
        }
        if (plan.getStatus() != PlanStatus.ACTIVE && plan.getStatus() != PlanStatus.COMPLETED) {
            throw new IllegalStateException("Plan " + plan.getId() + " is " + plan.getStatus() + " and can't be refunded");
        }

        if (plan.getStatus() == PlanStatus.ACTIVE && installments(plan).get(0).getStatus() != InstallmentStatus.PAID) {
            Optional<PaddleWebhookData> paid = paddleClient.findCompletedTransaction(plan.getPaddleInitialTransactionId());
            if (paid.isPresent()) {
                // Paid in Paddle but not recorded yet: record it the normal way first, then refund it below.
                webhookService.handle("transaction.completed", paid.get());
            } else {
                // ponytail: if Paddle cancels but our commit fails, retries fail on the already-cancelled transaction
                // (same ceiling as FirstPaymentExpiryJob); treat Paddle's "already canceled" as success if it shows up.
                paddleClient.cancelTransaction(plan.getPaddleInitialTransactionId());
            }
        }

        List<Installment> installments = installments(plan);
        Set<String> paidTransactions = new LinkedHashSet<>();
        installments.stream()
                .filter(i -> i.getStatus() == InstallmentStatus.PAID)
                .forEach(i -> paidTransactions.add(i.getPaddleTransactionId()));
        Map<String, String> adjustments = new HashMap<>();
        for (String transactionId : paidTransactions) {
            adjustments.put(transactionId, paddleClient.refundTransaction(transactionId));
        }
        if (!paidTransactions.isEmpty() && plan.getStatus() == PlanStatus.ACTIVE) {
            paddleClient.cancelSubscription(plan.getPaddleSubscriptionId());
        }

        for (Installment installment : installments) {
            if (installment.getStatus() == InstallmentStatus.PAID) {
                installment.markRefunded(adjustments.get(installment.getPaddleTransactionId()));
            } else {
                installment.markCancelled();
            }
        }
        plan.markRefunded();
        BigDecimal total = refundedTotal(installments);
        publish(plan, total);
        log.info("Refunded plan {} for application {}: {} back to the shopper", plan.getId(), applicationId, total);
    }

    private List<Installment> installments(RepaymentPlan plan) {
        return installmentRepository.findByRepaymentPlanOrderBySequenceNumberAsc(plan);
    }

    private static BigDecimal refundedTotal(List<Installment> installments) {
        return installments.stream()
                .filter(i -> i.getStatus() == InstallmentStatus.REFUNDED)
                .map(Installment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void publish(RepaymentPlan plan, BigDecimal refundedAmount) {
        EventEnvelope<Object> envelope = EventEnvelope.of(TOPIC, plan.getId(), new RepaymentEvents.PlanRefunded(
                plan.getApplicantId(), plan.getApplicationId(), plan.getId(), refundedAmount));
        outboxEventRepository.save(new OutboxEvent(TOPIC, plan.getApplicationId().toString(), writeJson(envelope)));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize outbox payload", ex);
        }
    }
}
