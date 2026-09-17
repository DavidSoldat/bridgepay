package com.bridgepay.repayment.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 3 retries / 1s backoff, then log and skip (offset still commits) - no dead
 * letter topic, matching Notifications Service's exact configuration and the
 * spec's other "defer heavier infra" calls. If Paddle's circuit is open long
 * enough that retries exhaust, that application's repayment plan is dropped
 * after a loud log line - an accepted v1 tradeoff.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(this::logExhausted, new FixedBackOff(1000L, 3));
    }

    private void logExhausted(ConsumerRecord<?, ?> record, Exception ex) {
        log.error("Giving up on record from topic {} partition {} offset {} after retries exhausted: {}",
                record.topic(), record.partition(), record.offset(), ex.getMessage(), ex);
    }
}
