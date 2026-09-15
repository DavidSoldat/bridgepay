package com.bridgepay.application.service;

import com.bridgepay.application.domain.OutboxEvent;
import com.bridgepay.application.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    @SuppressWarnings("unchecked")
    void publishPending_marksEventPublished_whenSendSucceeds() {
        OutboxEvent event = new OutboxEvent("applications.approved", "agg-1", "{}");
        when(outboxEventRepository.findTop50ByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        new OutboxPublisher(outboxEventRepository, kafkaTemplate).publishPending();

        assertThat(event.isPublished()).isTrue();
    }

    @Test
    void publishPending_leavesEventUnpublished_whenSendFails() {
        OutboxEvent event = new OutboxEvent("applications.approved", "agg-1", "{}");
        when(outboxEventRepository.findTop50ByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));

        new OutboxPublisher(outboxEventRepository, kafkaTemplate).publishPending();

        assertThat(event.isPublished()).isFalse();
    }
}
