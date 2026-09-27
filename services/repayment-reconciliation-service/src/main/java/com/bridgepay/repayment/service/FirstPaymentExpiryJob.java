package com.bridgepay.repayment.service;

import com.bridgepay.repayment.client.PaddleClient;
import com.bridgepay.repayment.domain.InstallmentStatus;
import com.bridgepay.repayment.domain.OutboxEvent;
import com.bridgepay.repayment.domain.PlanStatus;
import com.bridgepay.repayment.domain.RepaymentPlan;
import com.bridgepay.repayment.event.EventEnvelope;
import com.bridgepay.repayment.event.RepaymentEvents;
import com.bridgepay.repayment.repository.OutboxEventRepository;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cancels orders whose first installment - the one the shopper pays by hand through Paddle checkout - is
 * still unpaid after the expiry. Each plan gets its own transaction so one Paddle refusal (typically: the
 * shopper paid at the last second, and a completed transaction can't be cancelled) doesn't block the rest.
 */
@Component
public class FirstPaymentExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(FirstPaymentExpiryJob.class);
    private static final List<InstallmentStatus> UNPAID = List.of(InstallmentStatus.SCHEDULED, InstallmentStatus.LATE);
    private static final String TOPIC = "repayments.plan-cancelled";

    private final RepaymentPlanRepository repaymentPlanRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PaddleClient paddleClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Duration expiry;

    public FirstPaymentExpiryJob(RepaymentPlanRepository repaymentPlanRepository,
                                 OutboxEventRepository outboxEventRepository,
                                 PaddleClient paddleClient,
                                 ObjectMapper objectMapper,
                                 TransactionTemplate transactionTemplate,
                                 @Value("${repayment.first-payment-expiry:PT24H}") Duration expiry) {
        this.repaymentPlanRepository = repaymentPlanRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.paddleClient = paddleClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.expiry = expiry;
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void run() {
        expireCreatedBefore(Instant.now().minus(expiry));
    }

    public void expireCreatedBefore(Instant cutoff) {
        List<UUID> planIds = repaymentPlanRepository.findIdsWithUnpaidFirstInstallmentCreatedBefore(
                cutoff, PlanStatus.ACTIVE, UNPAID);
        for (UUID planId : planIds) {
            try {
                transactionTemplate.executeWithoutResult(tx -> expire(planId));
            } catch (RuntimeException ex) {
                log.warn("Could not expire plan {}, will retry next run: {}", planId, ex.getMessage());
            }
        }
    }

    // ponytail: if Paddle cancels but our commit then fails, the next run's cancel hits an already-cancelled
    // transaction and keeps failing; treat Paddle's "already canceled" as success if that ever shows up.
    private void expire(UUID planId) {
        RepaymentPlan plan = repaymentPlanRepository.findById(planId).orElseThrow();
        if (plan.getStatus() != PlanStatus.ACTIVE) {
            return;
        }
        if (plan.getPaddleInitialTransactionId() != null) {
            paddleClient.cancelTransaction(plan.getPaddleInitialTransactionId());
        }
        plan.markCancelled();
        EventEnvelope<Object> envelope = EventEnvelope.of(TOPIC, plan.getId(),
                new RepaymentEvents.PlanCancelled(plan.getApplicantId(), plan.getApplicationId()));
        outboxEventRepository.save(new OutboxEvent(TOPIC, plan.getId().toString(), writeJson(envelope)));
        log.info("Cancelled plan {} for application {}: first installment unpaid", plan.getId(), plan.getApplicationId());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize outbox payload", ex);
        }
    }
}
