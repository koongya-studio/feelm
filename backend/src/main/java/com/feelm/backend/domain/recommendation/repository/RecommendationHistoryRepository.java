package com.feelm.backend.domain.recommendation.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecommendationHistoryRepository {

    private final EntityManager entityManager;

    public List<HistorySet> findSets(long userId, Instant cursorTime, long cursorSetId, int limit) {
        String cursorCondition =
                cursorTime == null
                        ? ""
                        : " AND (s.generated_at < :cursorTime"
                                + " OR (s.generated_at = :cursorTime AND s.id < :cursorSetId))";
        NativeQuery<Tuple> query =
                query(
                                """
                                SELECT s.id, s.generated_at
                                FROM recommendation_sets s
                                WHERE s.user_id = :userId
                                  AND EXISTS (
                                      SELECT 1 FROM recommendation_items i
                                      WHERE i.set_id = s.id AND i.exposed_at IS NOT NULL
                                  )
                                """
                                        + cursorCondition
                                        + " ORDER BY s.generated_at DESC, s.id DESC LIMIT :limit")
                        .setParameter("userId", userId)
                        .setParameter("limit", limit);
        if (cursorTime != null) {
            query.setParameter("cursorTime", cursorTime);
            query.setParameter("cursorSetId", cursorSetId);
        }
        return query.getResultList().stream()
                .map(
                        row ->
                                new HistorySet(
                                        row.get("id", Long.class),
                                        row.get("generated_at", Instant.class)))
                .toList();
    }

    public List<HistoryItem> findItems(long userId, List<Long> setIds) {
        if (setIds.isEmpty()) {
            return List.of();
        }
        return query(
                        """
                        SELECT i.set_id, i.id, i.type, cast(i.reason AS text) AS reason,
                               i.position, m.id AS movie_id, m.title, m.poster_url,
                               EXISTS (
                                   SELECT 1 FROM user_movie_dismissals d
                                   WHERE d.user_id = :userId AND d.movie_id = i.movie_id
                               ) AS dismissed,
                               CASE WHEN r.id IS NOT NULL THEN 'RATED' ELSE w.status END AS watch_status
                        FROM recommendation_items i
                        JOIN movies m ON m.id = i.movie_id
                        LEFT JOIN ratings r ON r.user_id = :userId AND r.movie_id = i.movie_id
                        LEFT JOIN LATERAL (
                            SELECT wr.status
                            FROM watch_records wr
                            WHERE wr.user_id = :userId AND wr.movie_id = i.movie_id
                            ORDER BY (wr.status <> 'EXPIRED') DESC, wr.id DESC
                            LIMIT 1
                        ) w ON true
                        WHERE i.set_id IN (:setIds) AND i.exposed_at IS NOT NULL
                        ORDER BY i.set_id, i.position, i.id
                        """)
                .setParameter("userId", userId)
                .setParameterList("setIds", setIds)
                .getResultList()
                .stream()
                .map(
                        row ->
                                new HistoryItem(
                                        row.get("set_id", Long.class),
                                        row.get("id", Long.class),
                                        row.get("type", String.class),
                                        row.get("reason", String.class),
                                        row.get("movie_id", Long.class),
                                        row.get("title", String.class),
                                        row.get("poster_url", String.class),
                                        row.get("dismissed", Boolean.class),
                                        row.get("watch_status", String.class)))
                .toList();
    }

    private NativeQuery<Tuple> query(String sql) {
        return entityManager.unwrap(Session.class).createNativeQuery(sql, Tuple.class);
    }

    public record HistorySet(long id, Instant generatedAt) {}

    public record HistoryItem(
            long setId,
            long id,
            String type,
            String reason,
            long movieId,
            String title,
            String posterUrl,
            boolean dismissed,
            String watchStatus) {}
}
