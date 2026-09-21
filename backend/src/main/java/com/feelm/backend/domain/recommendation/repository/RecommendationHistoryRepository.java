package com.feelm.backend.domain.recommendation.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;

/**
 * API-048 추천 이력을 읽는다.
 *
 * <p>정본은 {@code user_movie_exposures}다. {@code (user_id, movie_id)}가 기본키라 같은 영화의 재노출이 행을 늘리지 않으므로,
 * 읽는 쪽은 중복을 따로 걸러낼 필요가 없다(S15P21E106-671). 노출 시점에 한 번 정리하는 값을 조회 때마다 다시 계산하지 않는다.
 *
 * <p>그래서 두 질의 모두 사용자 이력 전체가 아니라 요청한 페이지만큼만 읽는다. 비용이 이력 길이에 비례해 자라지 않는다.
 *
 * <p>{@code recommendation_items}는 {@code type}·{@code reason}·{@code position}을 가져오려고만 조인한다. 이력 표는
 * 그 값들을 복사하지 않고 항목을 가리키기만 해서 같은 값이 두 군데 생기지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class RecommendationHistoryRepository {

    private final EntityManager entityManager;

    public List<HistorySet> findSets(long userId, long cursorSetId, int limit) {
        String cursorCondition = cursorSetId == 0 ? "" : " AND e.set_id < :cursorSetId";
        NativeQuery<Tuple> query =
                query(
                                """
                                SELECT s.id, s.generated_at
                                FROM (
                                    SELECT DISTINCT e.set_id
                                    FROM user_movie_exposures e
                                    WHERE e.user_id = :userId
                                """
                                        + cursorCondition
                                        + """
                                    ORDER BY e.set_id DESC
                                    LIMIT :limit
                                ) p
                                JOIN recommendation_sets s ON s.id = p.set_id
                                ORDER BY s.id DESC""")
                        .setParameter("userId", userId)
                        .setParameter("limit", limit);
        if (cursorSetId != 0) {
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
                        FROM user_movie_exposures e
                        JOIN recommendation_items i ON i.id = e.recommendation_item_id
                        JOIN movies m ON m.id = i.movie_id
                        LEFT JOIN ratings r ON r.user_id = :userId AND r.movie_id = i.movie_id
                        LEFT JOIN LATERAL (
                            SELECT wr.status
                            FROM watch_records wr
                            WHERE wr.user_id = :userId AND wr.movie_id = i.movie_id
                            ORDER BY (wr.status <> 'EXPIRED') DESC, wr.id DESC
                            LIMIT 1
                        ) w ON true
                        WHERE e.user_id = :userId AND e.set_id IN (:setIds)
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
