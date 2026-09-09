package com.feelm.backend.infrastructure.kafka;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PENDING 아웃박스를 저장된 Topic, key, JSON 그대로 Kafka에 전달하는 단일 폴러다.
 *
 * <p>다음 장애 격리 원칙을 유지한다.
 *
 * <ul>
 *   <li>기본 500ms 간격으로 실행하며 운영 환경에서 설정으로 조정할 수 있다.
 *   <li>Kafka 응답을 기다리는 동안 DB 트랜잭션을 유지하지 않는다.
 *   <li>일반적인 한 건의 발행 실패가 같은 배치의 다음 이벤트를 막지 않는다.
 *   <li>스레드 중단은 현재 이벤트의 재시도를 기록한 뒤 폴링을 종료한다.
 *   <li>실패 로그에는 식별 정보만 남기고 사용자 행동이 포함될 수 있는 JSON message는 남기지 않는다.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class KafkaOutboxRelay {

    private final OutboxEventStore store;
    private final KafkaTemplate<Long, String> kafkaTemplate;
    private final KafkaOutboxProperties properties;
    private final OutboxRetryPolicy retryPolicy;
    private final Clock clock;

    /** Kafka 응답 대기 전후의 DB 연산은 store의 별도 짧은 트랜잭션에서 실행된다. */
    @Scheduled(fixedDelayString = "${feelm.kafka.outbox.poll-interval:500ms}")
    void publishPending() {
        for (OutboxEvent event : store.findDue(Instant.now(clock), properties.batchSize())) {
            if (!publish(event)) {
                return;
            }
        }
    }

    @Scheduled(fixedDelayString = "${feelm.kafka.outbox.cleanup-interval:1h}")
    void deletePublished() {
        store.deletePublishedBefore(Instant.now(clock).minus(properties.publishedRetention()));
    }

    /**
     * 이벤트 한 건을 발행하고 배치 처리를 계속할지 반환한다.
     *
     * <p>일반적인 Kafka 실패는 PENDING으로 재예약한 뒤 {@code true}를 반환해 다음 이벤트를 처리한다. 애플리케이션 종료 요청 등에 따른 스레드 중단만
     * 인터럽트 상태를 복원하고 {@code false}를 반환해 현재 폴링을 끝낸다.
     */
    private boolean publish(OutboxEvent event) {
        Instant attemptedAt = Instant.now(clock);
        try {
            // 처음 저장한 Topic, userId key와 JSON을 변경 없이 보내고 Broker 결과를 확인한다.
            // Producer의 delivery timeout(기본 5초)이 완료 대기의 상한을 제한한다.
            kafkaTemplate.send(event.topic(), event.partitionKey(), event.message()).get();
            store.markPublished(event.id(), Instant.now(clock));
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            markFailed(event, attemptedAt, exception);
            return false;
        } catch (ExecutionException | RuntimeException exception) {
            markFailed(event, attemptedAt, exception);
            return true;
        }
    }

    private void markFailed(OutboxEvent event, Instant attemptedAt, Exception exception) {
        int attemptCount = event.attemptCount() + 1;
        Instant nextAttemptAt = attemptedAt.plus(retryPolicy.delay(attemptCount));
        String error =
                exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage();
        store.markFailed(event.id(), attemptedAt, nextAttemptAt, error);
        log.warn(
                "Kafka outbox publish failed: eventId={}, eventType={}, topic={}, attemptCount={}, nextAttemptAt={}",
                event.eventId(),
                event.eventType(),
                event.topic(),
                attemptCount,
                nextAttemptAt);
    }
}
