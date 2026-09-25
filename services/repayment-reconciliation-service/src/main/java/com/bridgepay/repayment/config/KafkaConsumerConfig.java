package com.bridgepay.repayment.config;

import com.bridgepay.repayment.domain.FailedEvent;
import com.bridgepay.repayment.repository.FailedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 3 retries / 1s backoff, then the record is saved to failed_events (see
 * FailedEvent) so ops can see and retry it from main-app, and the offset
 * commits so the partition keeps moving. If saving the row itself fails, the
 * record is only logged - no worse than before failed_events existed.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler(FailedEventRepository failedEventRepository) {
        return new DefaultErrorHandler((record, ex) -> recover(failedEventRepository, record, ex),
                new FixedBackOff(1000L, 3));
    }

    private void recover(FailedEventRepository failedEventRepository, ConsumerRecord<?, ?> record, Exception ex) {
        log.error("Giving up on record from topic {} partition {} offset {} after retries exhausted: {}",
                record.topic(), record.partition(), record.offset(), ex.getMessage(), ex);
        try {
            failedEventRepository.save(new FailedEvent(record.topic(),
                    record.key() == null ? null : record.key().toString(),
                    String.valueOf(record.value()), FailedEvent.describe(ex)));
        } catch (Exception saveFailure) {
            log.error("Could not record failed event from topic {} partition {} offset {}",
                    record.topic(), record.partition(), record.offset(), saveFailure);
        }
    }
}
