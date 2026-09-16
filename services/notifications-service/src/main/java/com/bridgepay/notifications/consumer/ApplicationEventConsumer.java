package com.bridgepay.notifications.consumer;

import com.bridgepay.notifications.domain.NotificationType;
import com.bridgepay.notifications.event.ApplicationEvents;
import com.bridgepay.notifications.event.EventEnvelope;
import com.bridgepay.notifications.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApplicationEventConsumer {

    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    public ApplicationEventConsumer(ObjectMapper objectMapper, NotificationService notificationService) {
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = "applications.approved")
    public void onApproved(String message) {
        EventEnvelope<ApplicationEvents.Approved> envelope = objectMapper.readValue(message,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, ApplicationEvents.Approved.class));
        ApplicationEvents.Approved payload = envelope.payload();
        notificationService.recordAndSend(envelope.eventId(), payload.applicantId(), NotificationType.APPLICATION_APPROVED,
                "your application for " + payload.amount() + " was approved (" + payload.installmentCount() + " installments of "
                        + payload.installmentAmount() + ")");
    }

    @KafkaListener(topics = "applications.manual-review")
    public void onManualReview(String message) {
        EventEnvelope<ApplicationEvents.ManualReview> envelope = objectMapper.readValue(message,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, ApplicationEvents.ManualReview.class));
        ApplicationEvents.ManualReview payload = envelope.payload();
        notificationService.recordAndSend(envelope.eventId(), payload.applicantId(), NotificationType.APPLICATION_MANUAL_REVIEW,
                "your application is under manual review");
    }

    @KafkaListener(topics = "applications.declined")
    public void onDeclined(String message) {
        EventEnvelope<ApplicationEvents.Declined> envelope = objectMapper.readValue(message,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, ApplicationEvents.Declined.class));
        ApplicationEvents.Declined payload = envelope.payload();
        notificationService.recordAndSend(envelope.eventId(), payload.applicantId(), NotificationType.APPLICATION_DECLINED,
                "your application was declined");
    }
}
