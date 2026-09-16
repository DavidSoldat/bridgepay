package com.bridgepay.notifications.service;

import com.bridgepay.notifications.domain.NotificationLog;
import com.bridgepay.notifications.domain.NotificationType;
import com.bridgepay.notifications.repository.NotificationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The one shared path every listener calls. The unique constraint on
 * event_id IS the idempotency check - no separate exists-then-insert race.
 *
 * Deliberately NOT @Transactional here: repository.saveAndFlush already runs
 * its own transactional boundary (Spring Data JPA repositories are
 * @Transactional out of the box). Wrapping it in a second, outer
 * @Transactional here would join that same physical transaction - the
 * constraint violation marks it rollback-only before this method's own catch
 * block ever sees it, so returning normally still throws
 * UnexpectedRollbackException on commit. Letting the repository own its
 * transaction means the violation is a clean, fully-finished rollback by the
 * time it reaches this catch.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationLogRepository repository;

    public NotificationService(NotificationLogRepository repository) {
        this.repository = repository;
    }

    public void recordAndSend(UUID eventId, UUID applicantId, NotificationType type, String message) {
        try {
            repository.saveAndFlush(new NotificationLog(eventId, applicantId, type));
        } catch (DataIntegrityViolationException ex) {
            log.debug("Duplicate delivery for event {}, skipping", eventId);
            return;
        }
        log.info("would send: {} to applicant {}: {}", type, applicantId, message);
    }
}
