package com.feelm.backend.infrastructure.kafka;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 모든 Kafka 이벤트가 공유하는 봉투다.
 *
 * <p>{@code eventId}는 Consumer의 중복 판정 기준이고 {@code userId}는 Kafka partition key와 동일하다.
 */
public record KafkaEventEnvelope<T>(
        UUID eventId, KafkaEventType eventType, Instant occurredAt, Long userId, T payload) {

    /** 필수 계약 필드가 빠진 이벤트가 아웃박스에 저장되지 않도록 생성 시점에 검증한다. */
    public KafkaEventEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }
}
