package com.feelm.backend.domain.user.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

/**
 * 프로필 요약(API-014)이 쓰는 활동 수 집계다. 읽기 전용이며 다른 도메인의 행을 바꾸지 않는다.
 *
 * <p>{@code frames}(BE-C)와 {@code ratings}(BE-B)는 이 저장소가 소유하지 않는 테이블이고 아직 Entity도 없어 파생 쿼리를 쓸 수 없다.
 * 그래서 Native Query를 사용한다(컨벤션 9장). 두 값을 스칼라 서브쿼리 하나로 받아 DB 왕복을 한 번으로 줄인다. 조건이 {@code user_id} 하나뿐이라
 * 소유 도메인이 집계 규칙을 바꿔도 이 질의가 함께 틀어질 여지가 없다.
 *
 * <p>팝콘 수는 여기서 세지 않는다. {@code ratings}와 {@code movies.representative_flavor_id}를 조합하는 규칙이 팝콘 버킷
 * (API-041)의 것과 같아야 하므로 {@code TasteQueryService}의 집계를 재사용한다(플랜 결정 D-9).
 */
@Repository
public class UserActivityCountRepository {

    private static final String COUNT_QUERY =
            """
            SELECT (SELECT COUNT(*) FROM frames WHERE user_id = :userId),
                   (SELECT COUNT(*) FROM ratings WHERE user_id = :userId)
            """;

    private final EntityManager entityManager;

    public UserActivityCountRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public ActivityCounts countByUserId(long userId) {
        Object[] row =
                (Object[])
                        entityManager
                                .createNativeQuery(COUNT_QUERY)
                                .setParameter("userId", userId)
                                .getSingleResult();
        return new ActivityCounts(toLong(row[0]), toLong(row[1]));
    }

    private static long toLong(Object value) {
        return ((Number) value).longValue();
    }

    public record ActivityCounts(long frameCount, long ratingCount) {}
}
