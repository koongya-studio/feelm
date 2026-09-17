package com.feelm.backend.infrastructure.kafka;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 도메인이 Kafka 설정을 알지 않고 이벤트를 현재 DB 트랜잭션의 아웃박스에 기록하는 공통 진입점이다. */
@Component
@RequiredArgsConstructor
public class KafkaEventPublisher {

    private final OutboxEventStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Supplier<UUID> eventIdSupplier;

    /** 호출한 도메인 작업과 같은 트랜잭션에 완성된 이벤트 JSON을 저장한다. */
    @Transactional
    public UUID publish(KafkaEventType eventType, long userId, Object payload) {
        if (eventType == KafkaEventType.PARTY_REFRESH) {
            throw new IllegalArgumentException(
                    "Party refresh requires its dedicated message format");
        }
        KafkaEventEnvelope<Object> event =
                new KafkaEventEnvelope<>(
                        eventIdSupplier.get(), eventType, Instant.now(clock), userId, payload);
        try {
            store.save(event, objectMapper.writeValueAsString(event));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    "Kafka event payload cannot be serialized", exception);
        }
        return event.eventId();
    }

    /** 완성된 파티 입력을 전용 JSON으로 현재 트랜잭션의 아웃박스에 저장한다. */
    @Transactional
    public UUID publishPartyRefresh(long partyId, Object payload) {
        if (partyId <= 0) {
            throw new IllegalArgumentException("Party ID must be positive");
        }
        UUID eventId = eventIdSupplier.get();
        try {
            store.savePartyRefresh(
                    eventId, partyId, objectMapper.writeValueAsString(payload), Instant.now(clock));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    "Party refresh payload cannot be serialized", exception);
        }
        return eventId;
    }
}
