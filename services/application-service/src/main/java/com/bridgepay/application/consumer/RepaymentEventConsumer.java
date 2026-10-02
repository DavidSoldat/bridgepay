package com.bridgepay.application.consumer;

import com.bridgepay.application.event.EventEnvelope;
import com.bridgepay.application.event.RepaymentEvents;
import com.bridgepay.application.service.CreditApplicationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Both handlers are naturally idempotent (state only moves forward), so no dedup table. */
@Component
public class RepaymentEventConsumer {

    private final ObjectMapper objectMapper;
    private final CreditApplicationService creditApplicationService;

    public RepaymentEventConsumer(ObjectMapper objectMapper, CreditApplicationService creditApplicationService) {
        this.objectMapper = objectMapper;
        this.creditApplicationService = creditApplicationService;
    }

    @KafkaListener(topics = "repayments.installment-paid")
    public void onInstallmentPaid(String message) {
        EventEnvelope<RepaymentEvents.InstallmentPaid> envelope = objectMapper.readValue(message,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, RepaymentEvents.InstallmentPaid.class));
        creditApplicationService.recordInstallmentPaid(envelope.payload().applicationId(), envelope.payload().sequenceNumber());
    }

    @KafkaListener(topics = "repayments.plan-completed")
    public void onPlanCompleted(String message) {
        EventEnvelope<RepaymentEvents.PlanCompleted> envelope = objectMapper.readValue(message,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, RepaymentEvents.PlanCompleted.class));
        creditApplicationService.completePlan(envelope.payload().applicationId());
    }

    @KafkaListener(topics = "repayments.plan-defaulted")
    public void onPlanDefaulted(String message) {
        EventEnvelope<RepaymentEvents.PlanDefaulted> envelope = objectMapper.readValue(message,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, RepaymentEvents.PlanDefaulted.class));
        creditApplicationService.defaultPlan(envelope.payload().applicationId());
    }

    @KafkaListener(topics = "repayments.plan-cancelled")
    public void onPlanCancelled(String message) {
        EventEnvelope<RepaymentEvents.PlanCancelled> envelope = objectMapper.readValue(message,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, RepaymentEvents.PlanCancelled.class));
        creditApplicationService.cancelUnpaidApplication(envelope.payload().applicationId());
    }
}
