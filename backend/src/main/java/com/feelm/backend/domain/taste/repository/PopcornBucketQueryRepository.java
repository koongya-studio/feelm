package com.feelm.backend.domain.taste.repository;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 사용자가 평가한 영화를 팝콘 맛별로 집계하는 읽기 전용 저장소다. */
@Repository
public class PopcornBucketQueryRepository {

    private final EntityManager entityManager;

    public PopcornBucketQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** 영화에 미리 저장한 대표 맛 FK를 사용해 해당 사용자의 평가만 맛별로 집계한다. */
    public List<PopcornFlavorAggregate> findRatedMoviesByFlavor(long userId) {
        return findRatedMoviesByFlavor(List.of(userId));
    }

    /** 여러 사용자의 팝콘 집계를 한 번에 조회해 파티 등 다른 취향 조회에서도 재사용한다. */
    public List<PopcornFlavorAggregate> findRatedMoviesByFlavor(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows =
                entityManager
                        .createNativeQuery(
                                """
                                SELECT r.user_id,
                                       m.representative_flavor_id,
                                       COUNT(r.id),
                                       AVG(r.score),
                                       MIN(r.created_at)
                                FROM ratings r
                                JOIN movies m ON m.id = r.movie_id
                                WHERE r.user_id IN (:userIds)
                                  AND m.representative_flavor_id IS NOT NULL
                                GROUP BY r.user_id, m.representative_flavor_id
                                """)
                        .setParameter("userIds", userIds)
                        .getResultList();

        return rows.stream().map(this::toAggregate).toList();
    }

    private PopcornFlavorAggregate toAggregate(Object[] row) {
        return new PopcornFlavorAggregate(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(),
                row[3] == null ? null : new BigDecimal(row[3].toString()),
                toInstant(row[4]));
    }

    /** H2와 PostgreSQL JDBC 드라이버의 timestamp 반환 타입 차이를 조회 경계에서 흡수한다. */
    private Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("지원하지 않는 평가 시각 타입입니다: " + value.getClass());
    }

    public record PopcornFlavorAggregate(
            long userId,
            long flavorId,
            long popcornCount,
            BigDecimal averageScore,
            Instant discoveredAt) {}
}
