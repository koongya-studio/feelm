package com.feelm.backend.infrastructure.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

@DisplayName("Kafka 아웃박스 relay")
class KafkaOutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    @DisplayName("Broker 성공 응답을 확인한 뒤 PUBLISHED로 변경한다")
    void marksPublishedAfterBrokerAcknowledgement() {
        OutboxEventStore store = mock(OutboxEventStore.class);
        KafkaTemplate<Long, String> template = mock(KafkaTemplate.class);
        OutboxEvent event = event(1L, 0);
        when(store.findDue(NOW, 100)).thenReturn(List.of(event));
        when(template.send(event.topic(), event.partitionKey(), event.message()))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay(store, template).publishPending();

        verify(store).markPublished(1L, NOW);
        verify(store, never()).markFailed(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("한 이벤트가 실패해도 PENDING으로 재예약하고 다음 이벤트를 발행한다")
    void retriesFailureAndContinuesBatch() {
        OutboxEventStore store = mock(OutboxEventStore.class);
        KafkaTemplate<Long, String> template = mock(KafkaTemplate.class);
        OutboxEvent failed = event(1L, 0);
        OutboxEvent succeeded = event(2L, 0);
        when(store.findDue(NOW, 100)).thenReturn(List.of(failed, succeeded));
        when(template.send(failed.topic(), failed.partitionKey(), failed.message()))
                .thenReturn(
                        CompletableFuture.failedFuture(new IllegalStateException("unavailable")));
        when(template.send(succeeded.topic(), succeeded.partitionKey(), succeeded.message()))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay(store, template).publishPending();

        verify(store)
                .markFailed(
                        1L,
                        NOW,
                        NOW.plusSeconds(1),
                        "java.lang.IllegalStateException: unavailable");
        verify(store).markPublished(2L, NOW);
    }

    private static KafkaOutboxRelay relay(
            OutboxEventStore store, KafkaTemplate<Long, String> template) {
        KafkaOutboxProperties properties =
                new KafkaOutboxProperties(
                        "localhost:9092", 100, Duration.ofSeconds(30), Duration.ofDays(7));
        return new KafkaOutboxRelay(
                store,
                template,
                properties,
                new OutboxRetryPolicy(Duration.ofSeconds(30), () -> 0.5),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OutboxEvent event(long id, int attemptCount) {
        return new OutboxEvent(
                id,
                UUID.randomUUID(),
                KafkaEventType.VIEW,
                "feelm.view.v1",
                1024L + id,
                "{}",
                attemptCount,
                NOW.minusSeconds(1));
    }
}
