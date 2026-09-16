package com.feelm.backend.domain.watch.repository;

import com.feelm.backend.domain.watch.cursor.WatchRecordCursor;
import com.feelm.backend.domain.watch.dto.request.WatchListCriteria;
import com.feelm.backend.domain.watch.model.WatchStatus;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public int expireDue(long userId, Instant now) {
        return jdbc.sql(
                        """
                        UPDATE watch_records
                        SET status = 'EXPIRED', expired_at = COALESCE(expired_at, :now)
                        WHERE user_id = :userId
                          AND status = 'LINK_CLICKED'
                          AND expires_at <= :now
                        """)
                .param("userId", userId)
                .param("now", offset(now))
                .update();
    }

    public List<WatchRecordListRow> findPage(
            long userId, WatchListCriteria criteria, WatchRecordCursor cursor, Instant now) {
        StringBuilder sql = new StringBuilder(listSelect()).append(" WHERE w.user_id = :userId");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("userId", userId);

        if (!criteria.statuses().isEmpty()) {
            sql.append(" AND w.status IN (:statuses)");
            parameters.put("statuses", criteria.statuses().stream().map(Enum::name).toList());
        }
        if (criteria.confirmDue()) {
            sql.append(
                    " AND w.status = 'LINK_CLICKED'"
                            + " AND w.confirm_due_at <= :now AND w.expires_at > :now");
            parameters.put("now", offset(now));
        }
        if (cursor != null && cursor.clickedAt() == null) {
            sql.append(" AND w.clicked_at IS NULL AND w.id < :cursorId");
            parameters.put("cursorId", cursor.watchRecordId());
        } else if (cursor != null) {
            sql.append(
                    " AND (w.clicked_at < :cursorClickedAt OR w.clicked_at IS NULL"
                            + " OR (w.clicked_at = :cursorClickedAt AND w.id < :cursorId))");
            parameters.put("cursorClickedAt", offset(cursor.clickedAt()));
            parameters.put("cursorId", cursor.watchRecordId());
        }
        sql.append(" ORDER BY w.clicked_at DESC NULLS LAST, w.id DESC LIMIT :limit");
        parameters.put("limit", criteria.size() + 1);
        return jdbc.sql(sql.toString()).params(parameters).query(this::mapListRow).list();
    }

    public Optional<OwnedWatchRecord> findOwnedForUpdate(long watchRecordId, long userId) {
        return jdbc.sql(
                        """
                        SELECT w.id, w.status, w.expires_at
                        FROM watch_records w
                        WHERE w.id = :watchRecordId AND w.user_id = :userId
                        FOR UPDATE OF w
                        """)
                .param("watchRecordId", watchRecordId)
                .param("userId", userId)
                .query(
                        (row, number) ->
                                new OwnedWatchRecord(
                                        row.getLong("id"),
                                        WatchStatus.valueOf(row.getString("status")),
                                        instant(row, "expires_at")))
                .optional();
    }

    public void confirm(long watchRecordId, boolean watched, Instant now) {
        int updated = watched ? markWatched(watchRecordId, now) : markExpired(watchRecordId, now);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.INVALID_WATCH_STATUS);
        }
    }

    private int markWatched(long watchRecordId, Instant now) {
        return jdbc.sql(
                        """
                        UPDATE watch_records
                        SET status = 'WATCHED_UNRATED', watched_at = :now
                        WHERE id = :watchRecordId
                          AND status = 'LINK_CLICKED'
                          AND expires_at > :now
                        """)
                .param("watchRecordId", watchRecordId)
                .param("now", offset(now))
                .update();
    }

    private int markExpired(long watchRecordId, Instant now) {
        return jdbc.sql(
                        """
                        UPDATE watch_records
                        SET status = 'EXPIRED', expired_at = :now
                        WHERE id = :watchRecordId
                          AND status = 'LINK_CLICKED'
                          AND expires_at > :now
                        """)
                .param("watchRecordId", watchRecordId)
                .param("now", offset(now))
                .update();
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

    private String listSelect() {
        return """
                SELECT w.id, w.movie_id, m.title, m.poster_url, w.status,
                       w.ott_id, o.code AS ott_code, w.clicked_at, w.confirm_due_at,
                       w.expires_at, w.watched_at, r.id AS rating_id
                FROM watch_records w
                JOIN movies m ON m.id = w.movie_id
                LEFT JOIN otts o ON o.id = w.ott_id
                LEFT JOIN ratings r ON r.watch_record_id = w.id
                                   AND r.user_id = w.user_id
                                   AND r.movie_id = w.movie_id
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

    private WatchRecordListRow mapListRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new WatchRecordListRow(
                resultSet.getLong("id"),
                resultSet.getLong("movie_id"),
                resultSet.getString("title"),
                resultSet.getString("poster_url"),
                WatchStatus.valueOf(resultSet.getString("status")),
                nullableLong(resultSet, "ott_id"),
                resultSet.getString("ott_code"),
                instant(resultSet, "clicked_at"),
                instant(resultSet, "confirm_due_at"),
                instant(resultSet, "expires_at"),
                instant(resultSet, "watched_at"),
                nullableLong(resultSet, "rating_id"));
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

    public record WatchRecordListRow(
            long watchRecordId,
            long movieId,
            String movieTitle,
            String posterUrl,
            WatchStatus status,
            Long ottId,
            String ottCode,
            Instant clickedAt,
            Instant confirmDueAt,
            Instant expiresAt,
            Instant watchedAt,
            Long ratingId) {}

    public record OwnedWatchRecord(long watchRecordId, WatchStatus status, Instant expiresAt) {}
}
