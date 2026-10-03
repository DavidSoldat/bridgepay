package com.bridgepay.notifications.service;

import com.bridgepay.notifications.AbstractKafkaIntegrationTest;
import com.bridgepay.notifications.domain.NotificationDraft;
import com.bridgepay.notifications.domain.NotificationType;
import com.bridgepay.notifications.repository.NotificationLogRepository;
import com.github.f4b6a3.uuid.UuidCreator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationServiceTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private NotificationService notificationService;
    @Autowired
    private NotificationLogRepository repository;

    @Test
    void recordAndSend_insertsOneRow() {
        UUID eventId = UuidCreator.getTimeOrderedEpoch();
        UUID applicantId = UuidCreator.getTimeOrderedEpoch();

        notificationService.recordAndSend(eventId, NotificationDraft.simple(applicantId,
                NotificationType.APPLICATION_APPROVED, "You're approved", "body text", null));

        var saved = repository.findAll().stream().filter(row -> row.getEventId().equals(eventId)).findFirst();
        assertThat(saved).isPresent();
        assertThat(saved.get().getApplicantId()).isEqualTo(applicantId);
        assertThat(saved.get().getType()).isEqualTo(NotificationType.APPLICATION_APPROVED);
        assertThat(saved.get().getTitle()).isEqualTo("You're approved");
        assertThat(saved.get().getBody()).isEqualTo("body text");
    }

    @Test
    void recordAndSend_skipsDuplicateEventId_withoutThrowing() {
        UUID eventId = UuidCreator.getTimeOrderedEpoch();
        UUID applicantId = UuidCreator.getTimeOrderedEpoch();

        notificationService.recordAndSend(eventId, NotificationDraft.simple(applicantId,
                NotificationType.APPLICATION_DECLINED, "t", "first delivery", null));
        notificationService.recordAndSend(eventId, NotificationDraft.simple(applicantId,
                NotificationType.APPLICATION_DECLINED, "t", "redelivered", null));

        long matching = repository.findAll().stream().filter(row -> row.getEventId().equals(eventId)).count();
        assertThat(matching).isEqualTo(1);
    }
}
