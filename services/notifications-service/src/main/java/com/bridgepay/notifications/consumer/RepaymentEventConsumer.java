package com.bridgepay.notifications.consumer;

import com.bridgepay.notifications.event.EventEnvelope;
import com.bridgepay.notifications.event.RepaymentEvents;
import com.bridgepay.notifications.service.NotificationCopy;
import com.bridgepay.notifications.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class RepaymentEventConsumer {

    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    public RepaymentEventConsumer(ObjectMapper objectMapper, NotificationService notificationService) {
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = "repayments.installment-paid")
    public void onInstallmentPaid(String message) {
        EventEnvelope<RepaymentEvents.InstallmentPaid> envelope = objectMapper.readValue(message,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, RepaymentEvents.InstallmentPaid.class));
        RepaymentEvents.InstallmentPaid payload = envelope.payload();
        notificationService.recordAndSend(envelope.eventId(), NotificationCopy.installmentPaid(payload));
    }

    @KafkaListener(topics = "repayments.installment-missed")
    public void onInstallmentMissed(String message) {
        EventEnvelope<RepaymentEvents.InstallmentMissed> envelope = objectMapper.readValue(message,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, RepaymentEvents.InstallmentMissed.class));
        RepaymentEvents.InstallmentMissed payload = envelope.payload();
        notificationService.recordAndSend(envelope.eventId(), NotificationCopy.installmentMissed(payload));
    }

    @KafkaListener(topics = "repayments.plan-completed")
    public void onPlanCompleted(String message) {
        EventEnvelope<RepaymentEvents.PlanCompleted> envelope = objectMapper.readValue(message,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, RepaymentEvents.PlanCompleted.class));
        RepaymentEvents.PlanCompleted payload = envelope.payload();
        notificationService.recordAndSend(envelope.eventId(), NotificationCopy.planCompleted(payload));
    }

    @KafkaListener(topics = "repayments.plan-defaulted")
    public void onPlanDefaulted(String message) {
        EventEnvelope<RepaymentEvents.PlanDefaulted> envelope = objectMapper.readValue(message,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, RepaymentEvents.PlanDefaulted.class));
        RepaymentEvents.PlanDefaulted payload = envelope.payload();
        notificationService.recordAndSend(envelope.eventId(), NotificationCopy.planDefaulted(payload));
    }

    @KafkaListener(topics = "repayments.plan-cancelled")
    public void onPlanCancelled(String message) {
        EventEnvelope<RepaymentEvents.PlanCancelled> envelope = objectMapper.readValue(message,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, RepaymentEvents.PlanCancelled.class));
        RepaymentEvents.PlanCancelled payload = envelope.payload();
        notificationService.recordAndSend(envelope.eventId(), NotificationCopy.planCancelled(payload));
    }
}
