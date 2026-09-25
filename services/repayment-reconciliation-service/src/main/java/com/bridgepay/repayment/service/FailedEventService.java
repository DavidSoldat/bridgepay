package com.bridgepay.repayment.service;

import com.bridgepay.repayment.consumer.ApplicationEventConsumer;
import com.bridgepay.repayment.domain.FailedEvent;
import com.bridgepay.repayment.domain.FailedEventStatus;
import com.bridgepay.repayment.dto.FailedEventResponse;
import com.bridgepay.repayment.repository.FailedEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Lists and retries failed_events rows. Retry replays the stored payload
 * in-process through the same listener method Kafka would call - safe
 * because createPlanFromApprovedApplication is idempotent per application.
 * Deliberately not @Transactional: the replay runs its own transaction, and
 * wrapping it would mark this one rollback-only when the replay throws.
 */
@Service
public class FailedEventService {

    static final String APPROVED_TOPIC = "applications.approved";

    private final FailedEventRepository failedEventRepository;
    private final ApplicationEventConsumer applicationEventConsumer;

    public FailedEventService(FailedEventRepository failedEventRepository,
                              ApplicationEventConsumer applicationEventConsumer) {
        this.failedEventRepository = failedEventRepository;
        this.applicationEventConsumer = applicationEventConsumer;
    }

    public Page<FailedEventResponse> list(String status, Pageable pageable) {
        Page<FailedEvent> page = "ALL".equals(status)
                ? failedEventRepository.findAllByOrderByCreatedAtDesc(pageable)
                : failedEventRepository.findByStatusOrderByCreatedAtDesc(FailedEventStatus.valueOf(status), pageable);
        return page.map(FailedEventResponse::from);
    }

    public FailedEventResponse retry(UUID id) {
        FailedEvent event = failedEventRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No failed event " + id));
        if (event.getStatus() == FailedEventStatus.RESOLVED) {
            throw new IllegalStateException("Failed event " + id + " is already resolved");
        }
        if (!APPROVED_TOPIC.equals(event.getTopic())) {
            throw new IllegalStateException("No retry handler for topic " + event.getTopic());
        }
        try {
            applicationEventConsumer.onApproved(event.getPayload());
            event.markResolved();
        } catch (Exception ex) {
            event.recordFailedRetry(FailedEvent.describe(ex));
        }
        return FailedEventResponse.from(failedEventRepository.save(event));
    }
}
