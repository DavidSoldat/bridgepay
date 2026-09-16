package com.bridgepay.notifications.consumer;

import com.bridgepay.notifications.AbstractKafkaIntegrationTest;
import com.bridgepay.notifications.domain.NotificationType;
import com.bridgepay.notifications.event.EventEnvelope;
import com.bridgepay.notifications.event.RepaymentEvents;
import com.bridgepay.notifications.repository.NotificationLogRepository;
import com.github.f4b6a3.uuid.UuidCreator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RepaymentEventConsumerIntegrationTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private NotificationLogRepository repository;

    @Test
    void installmentPaidEvent_isRecorded() {
        UUID eventId = UuidCreator.getTimeOrderedEpoch();
        UUID applicantId = UuidCreator.getTimeOrderedEpoch();
        UUID planId = UuidCreator.getTimeOrderedEpoch();
        var envelope = new EventEnvelope<>(eventId, "repayment.installment-paid", Instant.now(), planId, 1,
                new RepaymentEvents.InstallmentPaid(applicantId, UuidCreator.getTimeOrderedEpoch(), 1, new BigDecimal("50.00")));

        publish("repayments.installment-paid", planId.toString(), objectMapper.writeValueAsString(envelope));

        awaitNotificationLogged(eventId, applicantId, NotificationType.INSTALLMENT_PAID);
    }

    @Test
    void installmentMissedEvent_isRecorded() {
        UUID eventId = UuidCreator.getTimeOrderedEpoch();
        UUID applicantId = UuidCreator.getTimeOrderedEpoch();
        UUID planId = UuidCreator.getTimeOrderedEpoch();
        var envelope = new EventEnvelope<>(eventId, "repayment.installment-missed", Instant.now(), planId, 1,
                new RepaymentEvents.InstallmentMissed(applicantId, UuidCreator.getTimeOrderedEpoch(), 2, LocalDate.now()));

        publish("repayments.installment-missed", planId.toString(), objectMapper.writeValueAsString(envelope));

        awaitNotificationLogged(eventId, applicantId, NotificationType.INSTALLMENT_MISSED);
    }

    @Test
    void planCompletedEvent_isRecorded() {
        UUID eventId = UuidCreator.getTimeOrderedEpoch();
        UUID applicantId = UuidCreator.getTimeOrderedEpoch();
        UUID planId = UuidCreator.getTimeOrderedEpoch();
        var envelope = new EventEnvelope<>(eventId, "repayment.plan-completed", Instant.now(), planId, 1,
                new RepaymentEvents.PlanCompleted(applicantId, UuidCreator.getTimeOrderedEpoch()));

        publish("repayments.plan-completed", planId.toString(), objectMapper.writeValueAsString(envelope));

        awaitNotificationLogged(eventId, applicantId, NotificationType.PLAN_COMPLETED);
    }

    @Test
    void planDefaultedEvent_isRecorded() {
        UUID eventId = UuidCreator.getTimeOrderedEpoch();
        UUID applicantId = UuidCreator.getTimeOrderedEpoch();
        UUID planId = UuidCreator.getTimeOrderedEpoch();
        var envelope = new EventEnvelope<>(eventId, "repayment.plan-defaulted", Instant.now(), planId, 1,
                new RepaymentEvents.PlanDefaulted(applicantId, UuidCreator.getTimeOrderedEpoch()));

        publish("repayments.plan-defaulted", planId.toString(), objectMapper.writeValueAsString(envelope));

        awaitNotificationLogged(eventId, applicantId, NotificationType.PLAN_DEFAULTED);
    }

    @Test
    void redeliveredEvent_doesNotInsertSecondRow() {
        UUID eventId = UuidCreator.getTimeOrderedEpoch();
        UUID applicantId = UuidCreator.getTimeOrderedEpoch();
        UUID planId = UuidCreator.getTimeOrderedEpoch();
        var envelope = new EventEnvelope<>(eventId, "repayment.plan-defaulted", Instant.now(), planId, 1,
                new RepaymentEvents.PlanDefaulted(applicantId, UuidCreator.getTimeOrderedEpoch()));
        String json = objectMapper.writeValueAsString(envelope);

        publish("repayments.plan-defaulted", planId.toString(), json);
        awaitNotificationLogged(eventId, applicantId, NotificationType.PLAN_DEFAULTED);

        publish("repayments.plan-defaulted", planId.toString(), json);

        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(repository.findAll().stream().filter(row -> row.getEventId().equals(eventId)).count()).isEqualTo(1));
    }

    private void awaitNotificationLogged(UUID eventId, UUID applicantId, NotificationType type) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var saved = repository.findAll().stream().filter(row -> row.getEventId().equals(eventId)).findFirst();
            assertThat(saved).isPresent();
            assertThat(saved.get().getApplicantId()).isEqualTo(applicantId);
            assertThat(saved.get().getType()).isEqualTo(type);
        });
    }
}
