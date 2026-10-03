package com.bridgepay.notifications.consumer;

import com.bridgepay.notifications.AbstractKafkaIntegrationTest;
import com.bridgepay.notifications.domain.NotificationLog;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RepaymentEventConsumerIntegrationTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private NotificationLogRepository repository;

    @Test
    void installmentPaidEvent_isRecordedWithCopyAndGroup() {
        UUID eventId = UuidCreator.getTimeOrderedEpoch();
        UUID applicantId = UuidCreator.getTimeOrderedEpoch();
        UUID applicationId = UuidCreator.getTimeOrderedEpoch();
        UUID planId = UuidCreator.getTimeOrderedEpoch();
        var envelope = new EventEnvelope<>(eventId, "repayment.installment-paid", Instant.now(), planId, 1,
                new RepaymentEvents.InstallmentPaid(applicantId, applicationId, UuidCreator.getTimeOrderedEpoch(), 2,
                        new BigDecimal("17.44"), "txn_group_1"));

        publish("repayments.installment-paid", planId.toString(), objectMapper.writeValueAsString(envelope));

        NotificationLog row = awaitNotificationLogged(eventId, applicantId, NotificationType.INSTALLMENT_PAID);
        assertThat(row.getTitle()).isEqualTo("Payment 2 received");
        assertThat(row.getBody()).isEqualTo("$17.44");
        assertThat(row.getGroupKey()).isEqualTo("txn_group_1");
        assertThat(row.getSequenceNumber()).isEqualTo(2);
        assertThat(row.getApplicationId()).isEqualTo(applicationId);
    }

    @Test
    void installmentPaid_withoutTransactionId_isNotGrouped() {
        UUID eventId = UuidCreator.getTimeOrderedEpoch();
        UUID applicantId = UuidCreator.getTimeOrderedEpoch();
        UUID planId = UuidCreator.getTimeOrderedEpoch();
        // an event produced before repayment-reconciliation added paddleTransactionId
        String json = objectMapper.writeValueAsString(java.util.Map.of(
                "eventId", eventId.toString(), "eventType", "repayment.installment-paid",
                "occurredAt", Instant.now().toString(), "aggregateId", planId.toString(), "schemaVersion", 1,
                "payload", java.util.Map.of("applicantId", applicantId.toString(),
                        "applicationId", UuidCreator.getTimeOrderedEpoch().toString(),
                        "installmentId", UuidCreator.getTimeOrderedEpoch().toString(),
                        "sequenceNumber", 1, "amount", "17.44")));

        publish("repayments.installment-paid", planId.toString(), json);

        NotificationLog row = awaitNotificationLogged(eventId, applicantId, NotificationType.INSTALLMENT_PAID);
        assertThat(row.getGroupKey()).isNull();
        assertThat(row.getTitle()).isEqualTo("Payment 1 received");
    }

    @Test
    void planCancelledEvent_isRecorded() {
        UUID eventId = UuidCreator.getTimeOrderedEpoch();
        UUID applicantId = UuidCreator.getTimeOrderedEpoch();
        UUID applicationId = UuidCreator.getTimeOrderedEpoch();
        UUID planId = UuidCreator.getTimeOrderedEpoch();
        var envelope = new EventEnvelope<>(eventId, "repayments.plan-cancelled", Instant.now(), planId, 1,
                new RepaymentEvents.PlanCancelled(applicantId, applicationId));

        publish("repayments.plan-cancelled", planId.toString(), objectMapper.writeValueAsString(envelope));

        NotificationLog row = awaitNotificationLogged(eventId, applicantId, NotificationType.PLAN_CANCELLED);
        assertThat(row.getTitle()).isEqualTo("Order cancelled");
        assertThat(row.getApplicationId()).isEqualTo(applicationId);
    }

    @Test
    void installmentMissedEvent_isRecorded() {
        UUID eventId = UuidCreator.getTimeOrderedEpoch();
        UUID applicantId = UuidCreator.getTimeOrderedEpoch();
        UUID applicationId = UuidCreator.getTimeOrderedEpoch();
        UUID planId = UuidCreator.getTimeOrderedEpoch();
        var envelope = new EventEnvelope<>(eventId, "repayment.installment-missed", Instant.now(), planId, 1,
                new RepaymentEvents.InstallmentMissed(applicantId, applicationId, UuidCreator.getTimeOrderedEpoch(), 2, LocalDate.now()));

        publish("repayments.installment-missed", planId.toString(), objectMapper.writeValueAsString(envelope));

        NotificationLog row = awaitNotificationLogged(eventId, applicantId, NotificationType.INSTALLMENT_MISSED);
        assertThat(row.getApplicationId()).isEqualTo(applicationId);
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

    private NotificationLog awaitNotificationLogged(UUID eventId, UUID applicantId, NotificationType type) {
        AtomicReference<NotificationLog> found = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var saved = repository.findAll().stream().filter(row -> row.getEventId().equals(eventId)).findFirst();
            assertThat(saved).isPresent();
            assertThat(saved.get().getApplicantId()).isEqualTo(applicantId);
            assertThat(saved.get().getType()).isEqualTo(type);
            found.set(saved.get());
        });
        return found.get();
    }
}
