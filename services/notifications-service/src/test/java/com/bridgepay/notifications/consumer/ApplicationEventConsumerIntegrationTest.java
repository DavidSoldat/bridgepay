package com.bridgepay.notifications.consumer;

import com.bridgepay.notifications.AbstractKafkaIntegrationTest;
import com.bridgepay.notifications.domain.NotificationLog;
import com.bridgepay.notifications.domain.NotificationType;
import com.bridgepay.notifications.event.ApplicationEvents;
import com.bridgepay.notifications.event.EventEnvelope;
import com.bridgepay.notifications.repository.NotificationLogRepository;
import com.github.f4b6a3.uuid.UuidCreator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ApplicationEventConsumerIntegrationTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private NotificationLogRepository repository;

    @Test
    void approvedEvent_isRecorded() {
        UUID eventId = UuidCreator.getTimeOrderedEpoch();
        UUID applicantId = UuidCreator.getTimeOrderedEpoch();
        UUID applicationId = UuidCreator.getTimeOrderedEpoch();
        var envelope = new EventEnvelope<>(eventId, "application.approved", Instant.now(), applicationId, 1,
                new ApplicationEvents.Approved(applicantId, UuidCreator.getTimeOrderedEpoch(),
                        new BigDecimal("200.00"), 4, new BigDecimal("50.00")));

        publish("applications.approved", applicantId.toString(), objectMapper.writeValueAsString(envelope));

        NotificationLog row = awaitNotificationLogged(eventId, applicantId, NotificationType.APPLICATION_APPROVED);
        assertThat(row.getTitle()).isEqualTo("You're approved");
        assertThat(row.getBody()).isEqualTo("4 payments of $50.00 for your $200.00 order.");
        assertThat(row.getApplicationId()).isEqualTo(applicationId);
    }

    @Test
    void manualReviewEvent_isRecorded() {
        UUID eventId = UuidCreator.getTimeOrderedEpoch();
        UUID applicantId = UuidCreator.getTimeOrderedEpoch();
        var envelope = new EventEnvelope<>(eventId, "application.manual-review", Instant.now(), applicantId, 1,
                new ApplicationEvents.ManualReview(applicantId, 0.45));

        publish("applications.manual-review", applicantId.toString(), objectMapper.writeValueAsString(envelope));

        awaitNotificationLogged(eventId, applicantId, NotificationType.APPLICATION_MANUAL_REVIEW);
    }

    @Test
    void declinedEvent_isRecorded() {
        UUID eventId = UuidCreator.getTimeOrderedEpoch();
        UUID applicantId = UuidCreator.getTimeOrderedEpoch();
        var envelope = new EventEnvelope<>(eventId, "application.declined", Instant.now(), applicantId, 1,
                new ApplicationEvents.Declined(applicantId, 0.85));

        publish("applications.declined", applicantId.toString(), objectMapper.writeValueAsString(envelope));

        awaitNotificationLogged(eventId, applicantId, NotificationType.APPLICATION_DECLINED);
    }

    @Test
    void redeliveredEvent_doesNotInsertSecondRow() {
        UUID eventId = UuidCreator.getTimeOrderedEpoch();
        UUID applicantId = UuidCreator.getTimeOrderedEpoch();
        var envelope = new EventEnvelope<>(eventId, "application.declined", Instant.now(), applicantId, 1,
                new ApplicationEvents.Declined(applicantId, 0.85));
        String json = objectMapper.writeValueAsString(envelope);

        publish("applications.declined", applicantId.toString(), json);
        awaitNotificationLogged(eventId, applicantId, NotificationType.APPLICATION_DECLINED);

        publish("applications.declined", applicantId.toString(), json);

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
