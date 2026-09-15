package com.feelm.backend.domain.watch.repository;

import com.feelm.backend.domain.watch.model.WatchStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WatchRepository {

    private static final Duration CONFIRM_DELAY = Duration.ofDays(2);
    private static final Duration EXPIRATION_DELAY = Duration.ofDays(7);

    private final JdbcClient jdbc;

    /** 활성 사용자의 동시 클릭을 직렬화해 활성 감상 기록의 부분 유일 인덱스 경합을 막는다. */
    public boolean lockActiveUser(long userId) {
        return jdbc.sql(
                        "SELECT id FROM users WHERE id = :userId AND status = 'ACTIVE'"
                                + " FOR NO KEY UPDATE")
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .isPresent();
    }

    /** 영화·OTT·연결 행이 클릭 처리 중 삭제되지 않도록 실제 연결 조합을 잠가 확인한다. */
    public boolean movieOffersOtt(long movieId, long ottId) {
        return jdbc.sql(
                        """
                        SELECT provider.movie_id
                        FROM movie_ott_providers provider
                        JOIN movies movie ON movie.id = provider.movie_id
                        JOIN otts ott ON ott.id = provider.ott_id
                        WHERE provider.movie_id = :movieId AND provider.ott_id = :ottId
                        FOR KEY SHARE OF provider, movie, ott
                        """)
                .param("movieId", movieId)
                .param("ottId", ottId)
                .query(Long.class)
                .optional()
                .isPresent();
    }

    public Optional<WatchRecordSnapshot> findActiveForUpdate(long userId, long movieId) {
        return jdbc.sql(
                        baseSelect()
                                + " WHERE w.user_id = :userId AND w.movie_id = :movieId"
                                + " AND w.status <> 'EXPIRED' FOR UPDATE OF w")
                .param("userId", userId)
                .param("movieId", movieId)
                .query(this::mapRecord)
                .optional();
    }

    public WatchRecordSnapshot createClick(
            long userId, long movieId, long ottId, Instant clickedAt) {
        Instant confirmDueAt = clickedAt.plus(CONFIRM_DELAY);
        Instant expiresAt = clickedAt.plus(EXPIRATION_DELAY);
        long id =
                jdbc.sql(
                                """
                                INSERT INTO watch_records(
                                    user_id, movie_id, ott_id, status,
                                    clicked_at, confirm_due_at, expires_at
                                ) VALUES (
                                    :userId, :movieId, :ottId, 'LINK_CLICKED',
                                    :clickedAt, :confirmDueAt, :expiresAt
                                ) RETURNING id
                                """)
                        .param("userId", userId)
                        .param("movieId", movieId)
                        .param("ottId", ottId)
                        .param("clickedAt", offset(clickedAt))
                        .param("confirmDueAt", offset(confirmDueAt))
                        .param("expiresAt", offset(expiresAt))
                        .query(Long.class)
                        .single();
        return findById(id);
    }

    public WatchRecordSnapshot refreshClick(long watchRecordId, long ottId, Instant clickedAt) {
        int updated =
                jdbc.sql(
                                """
                        UPDATE watch_records
                        SET ott_id = :ottId,
                            clicked_at = :clickedAt,
                            confirm_due_at = :confirmDueAt,
                            expires_at = :expiresAt
                        WHERE id = :watchRecordId AND status = 'LINK_CLICKED'
                        """)
                        .param("ottId", ottId)
                        .param("clickedAt", offset(clickedAt))
                        .param("confirmDueAt", offset(clickedAt.plus(CONFIRM_DELAY)))
                        .param("expiresAt", offset(clickedAt.plus(EXPIRATION_DELAY)))
                        .param("watchRecordId", watchRecordId)
                        .update();
        requireSingleMutation(updated, "refresh", watchRecordId);
        return findById(watchRecordId);
    }

    public void expireClick(long watchRecordId, Instant now) {
        int updated =
                jdbc.sql(
                                """
                        UPDATE watch_records
                        SET status = 'EXPIRED', expired_at = COALESCE(expired_at, :now)
                        WHERE id = :watchRecordId AND status = 'LINK_CLICKED'
                        """)
                        .param("watchRecordId", watchRecordId)
                        .param("now", offset(now))
                        .update();
        requireSingleMutation(updated, "expire", watchRecordId);
    }

    private WatchRecordSnapshot findById(long watchRecordId) {
        return jdbc.sql(baseSelect() + " WHERE w.id = :watchRecordId")
                .param("watchRecordId", watchRecordId)
                .query(this::mapRecord)
                .single();
    }

    private String baseSelect() {
        return """
                SELECT w.id, w.movie_id, w.status, w.ott_id,
                       w.clicked_at, w.confirm_due_at, w.expires_at
                FROM watch_records w
                """;
    }

    private WatchRecordSnapshot mapRecord(ResultSet resultSet, int rowNumber) throws SQLException {
        return new WatchRecordSnapshot(
                resultSet.getLong("id"),
                resultSet.getLong("movie_id"),
                WatchStatus.valueOf(resultSet.getString("status")),
                nullableLong(resultSet, "ott_id"),
                instant(resultSet, "clicked_at"),
                instant(resultSet, "confirm_due_at"),
                instant(resultSet, "expires_at"));
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static void requireSingleMutation(int updated, String action, long watchRecordId) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "Failed to " + action + " locked watch record " + watchRecordId);
        }
    }

    public record WatchRecordSnapshot(
            long watchRecordId,
            long movieId,
            WatchStatus status,
            Long ottId,
            Instant clickedAt,
            Instant confirmDueAt,
            Instant expiresAt) {}
}
