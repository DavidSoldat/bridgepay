package com.bridgepay.application.service;

import com.bridgepay.application.domain.OutboxEvent;
import com.bridgepay.application.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Polls for unpublished outbox rows and sends them to Kafka. This is the
 * lighter-weight alternative to Debezium/CDC discussed in the Kafka contracts
 * section - simpler infrastructure at this scale, same delivery guarantee.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findTop50ByPublishedFalseOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getPartitionKey(), event.getPayload()).get();
                event.markPublished();
            } catch (Exception ex) {
                log.warn("Failed to publish outbox event {} to topic {}, will retry next poll",
                        event.getEventId(), event.getTopic(), ex);
            }
        }
    }
}
