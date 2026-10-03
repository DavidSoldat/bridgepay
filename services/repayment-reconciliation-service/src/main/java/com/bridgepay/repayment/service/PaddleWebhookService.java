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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every branch here is idempotent against Paddle's at-least-once webhook
 * delivery (and against RepaymentPlanService replaying transaction.completed
 * when a read finds Paddle ahead of us): a transaction already recorded on an
 * installment is skipped, and re-marking a settled plan is a no-op.
 */
@Service
public class PaddleWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaddleWebhookService.class);
    private static final List<InstallmentStatus> PENDING_STATUSES = List.of(InstallmentStatus.SCHEDULED, InstallmentStatus.LATE);

    private final RepaymentPlanRepository repaymentPlanRepository;
    private final InstallmentRepository installmentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PaddleClient paddleClient;
    private final ObjectMapper objectMapper;

    public PaddleWebhookService(RepaymentPlanRepository repaymentPlanRepository,
                                 InstallmentRepository installmentRepository,
                                 OutboxEventRepository outboxEventRepository,
                                 PaddleClient paddleClient,
                                 ObjectMapper objectMapper) {
        this.repaymentPlanRepository = repaymentPlanRepository;
        this.installmentRepository = installmentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.paddleClient = paddleClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void handle(String eventType, PaddleWebhookData data) {
        switch (eventType) {
            case "transaction.completed" -> handleTransactionCompleted(data);
            case "transaction.payment_failed", "subscription.past_due" -> handlePaymentFailed(data);
            case "subscription.canceled" -> handleSubscriptionCanceled(data);
            default -> log.debug("Ignoring unhandled Paddle event type {}", eventType);
        }
    }

    private void handleTransactionCompleted(PaddleWebhookData data) {
        RepaymentPlan found = findPlanByTransactionEvent(data);
        if (found == null) {
            log.warn("No repayment plan found for completed transaction {}", data.id());
            return;
        }
        // Row lock: the synchronous early-payment apply, Paddle's webhook and reconcile-on-read can all deliver the
        // same transaction at once; serialising here makes the dedupe check below reliable.
        RepaymentPlan plan = repaymentPlanRepository.findByIdForUpdate(found.getId()).orElse(found);
        if (data.subscriptionId() != null && !data.subscriptionId().equals(plan.getPaddleSubscriptionId())) {
            plan.adoptSubscriptionId(data.subscriptionId());
        }
        if (installmentRepository.existsByPaddleTransactionId(data.id())) {
            log.debug("Transaction {} already applied to plan {}, duplicate delivery", data.id(), plan.getId());
            return;
        }

        int covered = data.installmentsCovered();
        int paid = 0;
        for (; paid < covered; paid++) {
            Installment installment = nextPending(plan).orElse(null);
            if (installment == null) {
                log.debug("No pending installment left on plan {} for transaction {} ({} of {} applied)",
                        plan.getId(), data.id(), paid, covered);
                break;
            }

            installment.markPaid(data.id());
            writeOutbox("repayments.installment-paid", plan.getId(), "repayments.installment-paid", installment.getId(),
                    new RepaymentEvents.InstallmentPaid(plan.getApplicantId(), plan.getApplicationId(), installment.getId(),
                            installment.getSequenceNumber(), installment.getAmount(), data.id()));

            if (installment.getSequenceNumber() == plan.getInstallmentCount()) {
                plan.markCompleted();
                writeOutbox("repayments.plan-completed", plan.getId(), "repayments.plan-completed", plan.getId(),
                        new RepaymentEvents.PlanCompleted(plan.getApplicantId(), plan.getApplicationId()));
                paddleClient.cancelSubscription(plan.getPaddleSubscriptionId());
                return;
            }
        }
        if (data.isSubscriptionCharge() && paid > 0) {
            // Paddle's weekly renewals keep their original cadence after a pay-early charge, so each later renewal
            // pays the next pending installment `paid` weeks sooner than first scheduled: show the dates it will.
            int weeks = paid;
            installmentRepository.findByRepaymentPlanOrderBySequenceNumberAsc(plan).stream()
                    .filter(i -> i.getStatus() == InstallmentStatus.SCHEDULED)
                    .forEach(i -> i.moveDueDateEarlier(weeks));
        }
    }

    private void handlePaymentFailed(PaddleWebhookData data) {
        RepaymentPlan plan = findPlanByTransactionEvent(data);
        if (plan == null) {
            log.warn("No repayment plan found for failed payment {}", data.id());
            return;
        }

        Installment installment = installmentRepository
                .findFirstByRepaymentPlanAndStatusInOrderBySequenceNumberAsc(plan, List.of(InstallmentStatus.SCHEDULED))
                .orElse(null);
        if (installment == null) {
            log.debug("No newly-failing installment for plan {}, already marked late/paid", plan.getId());
            return;
        }

        installment.markLate();
        writeOutbox("repayments.installment-missed", plan.getId(), "repayments.installment-missed", installment.getId(),
                new RepaymentEvents.InstallmentMissed(plan.getApplicantId(), plan.getApplicationId(), installment.getId(),
                        installment.getSequenceNumber(), installment.getDueDate()));
    }

    private void handleSubscriptionCanceled(PaddleWebhookData data) {
        RepaymentPlan plan = repaymentPlanRepository.findByPaddleSubscriptionId(data.id()).orElse(null);
        if (plan == null) {
            log.warn("No repayment plan found for canceled subscription {}", data.id());
            return;
        }
        if (plan.getStatus() != PlanStatus.ACTIVE) {
            log.debug("Subscription {} cancel confirmed for plan {} already in status {}",
                    data.id(), plan.getId(), plan.getStatus());
            return;
        }

        plan.markDefaulted();
        nextPending(plan).ifPresent(Installment::markMissed);
        writeOutbox("repayments.plan-defaulted", plan.getId(), "repayments.plan-defaulted", plan.getId(),
                new RepaymentEvents.PlanDefaulted(plan.getApplicantId(), plan.getApplicationId()));
    }

    private Optional<Installment> nextPending(RepaymentPlan plan) {
        return installmentRepository.findFirstByRepaymentPlanAndStatusInOrderBySequenceNumberAsc(plan, PENDING_STATUSES);
    }

    /**
     * Matches by subscription_id once known; falls back to the plan's
     * placeholder (the initial transaction id) for the very first event,
     * before any subscription has been adopted yet.
     */
    private RepaymentPlan findPlanByTransactionEvent(PaddleWebhookData data) {
        if (data.subscriptionId() != null) {
            Optional<RepaymentPlan> bySubscription = repaymentPlanRepository.findByPaddleSubscriptionId(data.subscriptionId());
            if (bySubscription.isPresent()) {
                return bySubscription.get();
            }
        }
        return repaymentPlanRepository.findByPaddleSubscriptionId(data.id()).orElse(null);
    }

    private void writeOutbox(String topic, UUID partitionKey, String eventType, UUID aggregateId, Object payload) {
        EventEnvelope<Object> envelope = EventEnvelope.of(eventType, aggregateId, payload);
        outboxEventRepository.save(new OutboxEvent(topic, partitionKey.toString(), writeJson(envelope)));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize outbox payload", ex);
        }
    }
}
