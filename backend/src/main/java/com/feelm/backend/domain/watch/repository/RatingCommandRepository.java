package com.feelm.backend.domain.watch.repository;

import com.feelm.backend.domain.watch.model.RatingVisibility;
import com.feelm.backend.domain.watch.model.WatchStatus;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RatingCommandRepository {

    private final JdbcClient jdbc;

    public boolean lockActiveUser(long userId) {
        return jdbc.sql(
                        "SELECT id FROM users WHERE id = :userId AND status = 'ACTIVE'"
                                + " FOR NO KEY UPDATE")
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .isPresent();
    }

    public Optional<LockedMovie> lockMovie(long movieId) {
        return jdbc.sql(
                        """
                        SELECT movie.id, movie.representative_flavor_id, flavor.code
                        FROM movies movie
                        LEFT JOIN popcorn_flavors flavor
                          ON flavor.id = movie.representative_flavor_id
                        WHERE movie.id = :movieId
                        FOR UPDATE OF movie
                        """)
                .param("movieId", movieId)
                .query(
                        (row, number) ->
                                new LockedMovie(
                                        row.getLong("id"),
                                        nullableLong(row, "representative_flavor_id"),
                                        row.getString("code")))
                .optional();
    }

    public boolean ratingExists(long userId, long movieId) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM ratings"
                                + " WHERE user_id = :userId AND movie_id = :movieId)")
                .param("userId", userId)
                .param("movieId", movieId)
                .query(Boolean.class)
                .single();
    }

    public Optional<OwnedRating> findOwnedRating(long userId, long ratingId) {
        return jdbc.sql(
                        """
                        SELECT movie_id, watch_record_id
                        FROM ratings
                        WHERE id = :ratingId AND user_id = :userId
                        """)
                .param("ratingId", ratingId)
                .param("userId", userId)
                .query(
                        (row, number) ->
                                new OwnedRating(
                                        row.getLong("movie_id"),
                                        nullableLong(row, "watch_record_id")))
                .optional();
    }

    public Optional<LockedRating> lockOwnedRating(long userId, long ratingId) {
        return jdbc.sql(
                        """
                        SELECT id, movie_id, watch_record_id, score, comment,
                               visibility, is_spoiler, created_at
                        FROM ratings
                        WHERE id = :ratingId AND user_id = :userId
                        FOR UPDATE
                        """)
                .param("ratingId", ratingId)
                .param("userId", userId)
                .query(
                        (row, number) ->
                                new LockedRating(
                                        row.getLong("id"),
                                        row.getLong("movie_id"),
                                        nullableLong(row, "watch_record_id"),
                                        row.getBigDecimal("score"),
                                        row.getString("comment"),
                                        row.getString("visibility"),
                                        row.getBoolean("is_spoiler"),
                                        row.getObject("created_at", OffsetDateTime.class)
                                                .toInstant()))
                .optional();
    }

    public Optional<ExistingFrame> findFrame(long ratingId, long userId, long movieId) {
        return jdbc.sql(
                        """
                        SELECT id, created_at
                        FROM frames
                        WHERE rating_id = :ratingId AND user_id = :userId AND movie_id = :movieId
                        """)
                .param("ratingId", ratingId)
                .param("userId", userId)
                .param("movieId", movieId)
                .query(
                        (row, number) ->
                                new ExistingFrame(
                                        row.getLong("id"),
                                        row.getObject("created_at", OffsetDateTime.class)
                                                .toInstant()))
                .optional();
    }

    public void updateRating(
            long ratingId,
            long userId,
            BigDecimal score,
            String comment,
            RatingVisibility visibility,
            boolean spoiler,
            Instant updatedAt) {
        int updated =
                jdbc.sql(
                                """
                                UPDATE ratings
                                SET score = :score, comment = :comment, visibility = :visibility,
                                    is_spoiler = :spoiler, updated_at = :updatedAt
                                WHERE id = :ratingId AND user_id = :userId
                                """)
                        .param("ratingId", ratingId)
                        .param("userId", userId)
                        .param("score", score)
                        .param("comment", comment)
                        .param("visibility", visibility == null ? null : visibility.name())
                        .param("spoiler", spoiler)
                        .param("updatedAt", offset(updatedAt))
                        .update();
        if (updated != 1) {
            throw new IllegalStateException("수정 대상 평가 상태가 변경되었습니다.");
        }
    }

    public void deleteRating(long ratingId, long userId) {
        int deleted =
                jdbc.sql("DELETE FROM ratings WHERE id = :ratingId AND user_id = :userId")
                        .param("ratingId", ratingId)
                        .param("userId", userId)
                        .update();
        if (deleted != 1) {
            throw new IllegalStateException("삭제 대상 평가 상태가 변경되었습니다.");
        }
    }

    /**
     * 평가를 지운 감상 기록을 안 본 상태로 되돌린다(ADR-0025).
     *
     * <p>평가 삭제는 <b>그 영화를 보지 않은 것으로 되돌리는</b> 동작이다. 잘못 평가했으면 평가 화면에서 바로 지우고, 점수만 고칠 때는 삭제가 아니라
     * 수정(API-037)을 쓴다. 두 동작을 나누어 사용자에게 고르게 하지 않는다.
     *
     * <p>{@code watched_at}과 {@code rated_at}을 비우고 {@code expired_at}을 적는다. 감상 취소(API-035 {@code
     * watched:false})가 남기는 모양과 같아, 어느 경로로 왔든 {@code EXPIRED} 행이 같은 뜻을 갖는다.
     *
     * <p>행을 지우지 않는 이유는 이력이다(D-9). 되돌린 영화를 다시 보러 가면 {@code findActiveWatchForUpdate}가 {@code
     * EXPIRED}를 활성 기록으로 보지 않아 새 기록이 만들어진다.
     */
    public void markWatchExpired(long watchRecordId, long userId, long movieId, Instant expiredAt) {
        int updated =
                jdbc.sql(
                                """
                                UPDATE watch_records
                                SET status = 'EXPIRED', rated_at = NULL, watched_at = NULL,
                                    expired_at = :expiredAt
                                WHERE id = :watchRecordId AND user_id = :userId
                                  AND movie_id = :movieId AND status = 'RATED'
                                """)
                        .param("watchRecordId", watchRecordId)
                        .param("userId", userId)
                        .param("movieId", movieId)
                        .param("expiredAt", offset(expiredAt))
                        .update();
        if (updated != 1) {
            throw new IllegalStateException("평가에 연결된 감상 기록 상태가 변경되었습니다.");
        }
    }

    public Optional<ActiveWatchRecord> findActiveWatchForUpdate(long userId, long movieId) {
        return jdbc.sql(
                        """
                        SELECT id, status
                        FROM watch_records
                        WHERE user_id = :userId AND movie_id = :movieId
                          AND status <> 'EXPIRED'
                        FOR UPDATE
                        """)
                .param("userId", userId)
                .param("movieId", movieId)
                .query(
                        (row, number) ->
                                new ActiveWatchRecord(
                                        row.getLong("id"),
                                        WatchStatus.valueOf(row.getString("status"))))
                .optional();
    }

    public long createRatedWatch(long userId, long movieId, Instant ratedAt) {
        return jdbc.sql(
                        """
                        INSERT INTO watch_records(user_id, movie_id, status, rated_at)
                        VALUES (:userId, :movieId, 'RATED', :ratedAt)
                        RETURNING id
                        """)
                .param("userId", userId)
                .param("movieId", movieId)
                .param("ratedAt", offset(ratedAt))
                .query(Long.class)
                .single();
    }

    public void markWatchRated(long watchRecordId, Instant ratedAt) {
        int updated =
                jdbc.sql(
                                """
                                UPDATE watch_records
                                SET status = 'RATED', rated_at = :ratedAt
                                WHERE id = :watchRecordId AND status <> 'EXPIRED'
                                """)
                        .param("watchRecordId", watchRecordId)
                        .param("ratedAt", offset(ratedAt))
                        .update();
        if (updated != 1) {
            throw new IllegalStateException("평가할 활성 감상 기록 상태가 변경되었습니다.");
        }
    }

    public CreatedRating createRating(
            long userId,
            long movieId,
            long watchRecordId,
            BigDecimal score,
            String comment,
            RatingVisibility visibility,
            boolean spoiler,
            Instant createdAt) {
        return jdbc.sql(
                        """
                        INSERT INTO ratings(
                            user_id, movie_id, watch_record_id, score,
                            comment, visibility, is_spoiler, created_at, updated_at
                        ) VALUES (
                            :userId, :movieId, :watchRecordId, :score,
                            :comment, :visibility, :spoiler, :createdAt, :createdAt
                        )
                        RETURNING id, created_at
                        """)
                .param("userId", userId)
                .param("movieId", movieId)
                .param("watchRecordId", watchRecordId)
                .param("score", score)
                .param("comment", comment)
                .param("visibility", visibility == null ? null : visibility.name())
                .param("spoiler", spoiler)
                .param("createdAt", offset(createdAt))
                .query(
                        (row, number) ->
                                new CreatedRating(
                                        row.getLong("id"),
                                        row.getObject("created_at", OffsetDateTime.class)
                                                .toInstant()))
                .single();
    }

    public void refreshMovieRatingAggregate(long movieId) {
        int updated =
                jdbc.sql(
                                """
                                UPDATE movies movie
                                SET rating_count = aggregate.rating_count,
                                    average_rating = aggregate.average_rating
                                FROM (
                                    SELECT count(*)::int AS rating_count,
                                           coalesce(round(avg(score), 2), 0) AS average_rating
                                    FROM ratings
                                    WHERE movie_id = :movieId
                                ) aggregate
                                WHERE movie.id = :movieId
                                """)
                        .param("movieId", movieId)
                        .update();
        if (updated != 1) {
            throw new IllegalStateException("영화 평점 집계를 갱신하지 못했습니다.");
        }
    }

    public long countUserRatingsForFlavor(long userId, long flavorId) {
        return jdbc.sql(
                        """
                        SELECT count(*)
                        FROM ratings rating
                        JOIN movies movie ON movie.id = rating.movie_id
                        WHERE rating.user_id = :userId
                          AND movie.representative_flavor_id = :flavorId
                        """)
                .param("userId", userId)
                .param("flavorId", flavorId)
                .query(Long.class)
                .single();
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public record LockedMovie(long movieId, Long flavorId, String flavorCode) {}

    public record ActiveWatchRecord(long watchRecordId, WatchStatus status) {}

    public record CreatedRating(long ratingId, Instant createdAt) {}

    public record OwnedRating(long movieId, Long watchRecordId) {}

    public record LockedRating(
            long ratingId,
            long movieId,
            Long watchRecordId,
            BigDecimal score,
            String comment,
            String visibility,
            boolean spoiler,
            Instant createdAt) {}

    public record ExistingFrame(long frameId, Instant createdAt) {}
}
