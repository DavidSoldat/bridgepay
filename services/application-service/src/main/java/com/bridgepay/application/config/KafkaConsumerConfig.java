package com.bridgepay.application.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** 3 retries / 1s back-off, then log and move on (a stuck PENDING payout is visible in the merchant ledger). */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler((record, ex) -> log.error(
                "Giving up on record from topic {} partition {} offset {}: {}",
                record.topic(), record.partition(), record.offset(), ex.getMessage(), ex),
                new FixedBackOff(1000L, 3));
    }
}
