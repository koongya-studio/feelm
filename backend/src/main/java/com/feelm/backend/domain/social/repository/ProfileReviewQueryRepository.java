package com.feelm.backend.domain.social.repository;

import com.feelm.backend.domain.social.cursor.ProfileReviewCursor;
import com.feelm.backend.domain.social.dto.response.ProfileReviewPageResponse.Item;
import com.feelm.backend.domain.social.dto.response.ProfileReviewPageResponse.Movie;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProfileReviewQueryRepository {
    private final JdbcClient jdbc;

    public boolean isActiveViewer(long viewerId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM users WHERE id=:id AND status='ACTIVE')")
                .param("id", viewerId)
                .query(Boolean.class)
                .single();
    }

    public List<Item> findPage(long userId, ProfileReviewCursor cursor, int limit) {
        String sql =
                """
            SELECT r.id, r.movie_id, m.title, m.poster_url, r.score,
                   r.comment, r.is_spoiler, r.created_at
            FROM ratings r JOIN movies m ON m.id = r.movie_id
            WHERE r.user_id = :userId AND r.visibility = 'PUBLIC'
              AND r.comment IS NOT NULL AND %s
            """
                        .formatted(PublicReviewQueryRepository.NON_BLANK_COMMENT_PREDICATE);
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        params.put("limit", limit);
        if (cursor != null) {
            sql += " AND (r.created_at < :created OR (r.created_at = :created AND r.id < :id))";
            params.put("created", cursor.createdAt().atOffset(ZoneOffset.UTC));
            params.put("id", cursor.ratingId());
        }
        sql += " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit";
        return jdbc.sql(sql)
                .params(params)
                .query(
                        (r, n) ->
                                new Item(
                                        r.getLong("id"),
                                        new Movie(
                                                r.getLong("movie_id"),
                                                r.getString("title"),
                                                r.getString("poster_url")),
                                        r.getBigDecimal("score"),
                                        r.getString("comment"),
                                        r.getBoolean("is_spoiler"),
                                        r.getObject("created_at", OffsetDateTime.class)
                                                .toInstant()))
                .list();
    }
}
