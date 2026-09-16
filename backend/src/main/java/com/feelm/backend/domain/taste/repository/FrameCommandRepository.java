package com.feelm.backend.domain.taste.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FrameCommandRepository {

    private final JdbcClient jdbc;

    public FrameCommandRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 평가의 실제 소유자·영화가 모두 일치할 때만 프레임을 원자적으로 생성한다. */
    public Optional<CreatedFrame> create(
            long ratingId, long userId, long movieId, Instant createdAt) {
        return jdbc.sql(
                        """
                        INSERT INTO frames (rating_id, user_id, movie_id, created_at)
                        SELECT r.id, r.user_id, r.movie_id, :createdAt
                        FROM ratings r
                        WHERE r.id = :ratingId
                          AND r.user_id = :userId
                          AND r.movie_id = :movieId
                        ON CONFLICT (rating_id) DO NOTHING
                        RETURNING id, created_at
                        """)
                .param("ratingId", ratingId)
                .param("userId", userId)
                .param("movieId", movieId)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .query(
                        (row, rowNumber) ->
                                new CreatedFrame(
                                        row.getLong("id"),
                                        row.getObject("created_at", OffsetDateTime.class)
                                                .toInstant()))
                .optional();
    }

    public record CreatedFrame(long frameId, Instant createdAt) {}
}
