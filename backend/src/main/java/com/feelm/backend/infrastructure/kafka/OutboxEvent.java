package com.feelm.backend.infrastructure.kafka;

import java.time.Instant;
import java.util.UUID;

/** relay가 저장된 값 그대로 Kafka에 전송할 수 있도록 제공하는 아웃박스 조회 결과다. */
public record OutboxEvent(
        long id,
        UUID eventId,
        KafkaEventType eventType,
        String topic,
        long partitionKey,
        String message,
        int attemptCount,
        Instant createdAt) {}
