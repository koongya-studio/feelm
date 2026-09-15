package com.feelm.backend.infrastructure.kafka;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL 아웃박스의 짧은 저장·조회·상태 변경만 담당한다. */
@Repository
@RequiredArgsConstructor
class OutboxEventStore {

    private final JdbcClient jdbcClient;

    @Transactional
    long save(KafkaEventEnvelope<?> event, String message) {
        return jdbcClient
                .sql(
                        """
                        INSERT INTO outbox_events(
                            event_id, event_type, topic, partition_key, message,
                            status, attempt_count, next_attempt_at
                        ) VALUES (:eventId, :eventType, :topic, :partitionKey, CAST(:message AS jsonb),
                                  'PENDING', 0, :nextAttemptAt)
                        RETURNING id
                        """)
                .param("eventId", event.eventId())
                .param("eventType", event.eventType().name())
                .param("topic", event.eventType().topic())
                .param("partitionKey", event.userId())
                .param("message", message)
                .param("nextAttemptAt", toOffsetDateTime(event.occurredAt()))
                .query(Long.class)
                .single();
    }

    @Transactional(readOnly = true)
    List<OutboxEvent> findDue(Instant now, int batchSize) {
        return jdbcClient
                .sql(
                        """
                        SELECT id, event_id, event_type, topic, partition_key, message::text,
                               attempt_count, created_at
                        FROM outbox_events
                        WHERE status = 'PENDING' AND next_attempt_at <= :now
                        ORDER BY next_attempt_at, id
                        LIMIT :batchSize
                        """)
                .param("now", toOffsetDateTime(now))
                .param("batchSize", batchSize)
                .query(
                        (resultSet, rowNumber) ->
                                new OutboxEvent(
                                        resultSet.getLong("id"),
                                        resultSet.getObject("event_id", java.util.UUID.class),
                                        KafkaEventType.valueOf(resultSet.getString("event_type")),
                                        resultSet.getString("topic"),
                                        resultSet.getLong("partition_key"),
                                        resultSet.getString("message"),
                                        resultSet.getInt("attempt_count"),
                                        resultSet
                                                .getObject("created_at", OffsetDateTime.class)
                                                .toInstant()))
                .list();
    }

    @Transactional
    void markPublished(long id, Instant publishedAt) {
        jdbcClient
                .sql(
                        """
                        UPDATE outbox_events
                        SET status = 'PUBLISHED', published_at = :publishedAt,
                            last_attempt_at = :publishedAt, last_error = NULL
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", id)
                .param("publishedAt", toOffsetDateTime(publishedAt))
                .update();
    }

    @Transactional
    void markFailed(long id, Instant attemptedAt, Instant nextAttemptAt, String error) {
        jdbcClient
                .sql(
                        """
                        UPDATE outbox_events
                        SET attempt_count = attempt_count + 1, last_attempt_at = :attemptedAt,
                            next_attempt_at = :nextAttemptAt, last_error = :error
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", id)
                .param("attemptedAt", toOffsetDateTime(attemptedAt))
                .param("nextAttemptAt", toOffsetDateTime(nextAttemptAt))
                .param("error", error)
                .update();
    }

    @Transactional
    int deletePublishedBefore(Instant cutoff) {
        return jdbcClient
                .sql(
                        "DELETE FROM outbox_events WHERE status = 'PUBLISHED'"
                                + " AND published_at < :cutoff")
                .param("cutoff", toOffsetDateTime(cutoff))
                .update();
    }

    /** PostgreSQL timestamptz에 JDBC가 명확히 매핑할 수 있는 UTC 타입으로 변환한다. */
    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
