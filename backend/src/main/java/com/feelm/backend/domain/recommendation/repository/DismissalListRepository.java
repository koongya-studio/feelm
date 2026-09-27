package com.feelm.backend.domain.recommendation.repository;

import com.feelm.backend.domain.recommendation.dto.response.DismissalListItemResponse;
import com.feelm.backend.domain.recommendation.dto.response.DismissalListItemResponse.DismissalMovieResponse;
import com.feelm.backend.domain.recommendation.service.DismissalListCursor;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DismissalListRepository {
    private final JdbcTemplate jdbc;

    public boolean isActive(long userId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM users WHERE id = ? AND status = 'ACTIVE')",
                        Boolean.class,
                        userId));
    }

    public boolean hasCursor(long userId, DismissalListCursor cursor) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM user_movie_dismissals"
                                + " WHERE user_id = ? AND id = ? AND created_at = ?)",
                        Boolean.class,
                        userId,
                        cursor.id(),
                        Timestamp.from(cursor.createdAt())));
    }

    public List<DismissalListItemResponse> findPage(
            long userId, DismissalListCursor cursor, int limit) {
        String sql =
                "SELECT d.id, d.created_at, m.id AS movie_id, m.title, m.poster_url"
                        + " FROM user_movie_dismissals d JOIN movies m ON m.id = d.movie_id"
                        + " WHERE d.user_id = ?"
                        + (cursor == null ? "" : " AND (d.created_at, d.id) < (?, ?)")
                        + " ORDER BY d.created_at DESC, d.id DESC LIMIT ?";
        Object[] args =
                cursor == null
                        ? new Object[] {userId, limit}
                        : new Object[] {
                            userId, Timestamp.from(cursor.createdAt()), cursor.id(), limit
                        };
        return jdbc.query(
                sql,
                (rs, row) ->
                        new DismissalListItemResponse(
                                rs.getLong("id"),
                                new DismissalMovieResponse(
                                        rs.getLong("movie_id"),
                                        rs.getString("title"),
                                        rs.getString("poster_url")),
                                rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                args);
    }
}
