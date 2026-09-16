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
                                           round(avg(score), 2) AS average_rating
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
}
