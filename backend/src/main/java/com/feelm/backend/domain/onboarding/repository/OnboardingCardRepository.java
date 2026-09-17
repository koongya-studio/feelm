package com.feelm.backend.domain.onboarding.repository;

import com.feelm.backend.domain.onboarding.dto.response.OnboardingCardQueryResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 온보딩 카드 후보를 읽는다(API-023).
 *
 * <p>{@code movies}에는 Entity가 없고 콘텐츠 도메인이 적재 경로에서 직접 다루므로 Native Query를 쓴다(컨벤션 9장). 이 도메인은 {@code
 * movies}·{@code movie_genres}·{@code genres}·{@code popcorn_flavors}를 읽기만 하고 쓰지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class OnboardingCardRepository {

    /**
     * 업무 S15P21E106-542에서 확정한 영화 10편을 기획 순서대로 고른다.
     *
     * <p>영화와 대표 맛은 적재 데이터에서 읽으므로 제목·포스터·장르가 갱신되어도 최신 값을 반환한다. 다만 확정 목록에 없는 인기 영화로 자동 대체하지는 않는다. 목록에
     * 든 영화가 없거나 대표 맛이 배정되지 않았다면 조인에서 빠진다. 잘못된 적재를 다른 영화로 감추면 온보딩 취향 판별 구성이 달라지기 때문이다.
     */
    private static final String SELECT_CARDS =
            """
            WITH curated_movies(movie_id, display_order) AS (
                VALUES
                    (59738, 1),
                    (69823, 2),
                    (72564, 3),
                    (59664, 4),
                    (45504, 5),
                    (123, 6),
                    (76119, 7),
                    (88608, 8),
                    (100, 9),
                    (58868, 10)
            )
            SELECT m.id AS movie_id, m.title AS title, m.poster_url AS poster_url,
                   cast(extract(year from m.release_date) AS int) AS release_year,
                   f.code AS flavor_code
            FROM curated_movies c
            JOIN movies m ON m.id = c.movie_id
            JOIN popcorn_flavors f ON f.id = m.representative_flavor_id
            ORDER BY c.display_order
            LIMIT :size
            """;

    private static final String SELECT_GENRE_NAMES =
            """
            SELECT mg.movie_id AS movie_id, g.name AS name
            FROM movie_genres mg
            JOIN genres g ON g.id = mg.genre_id
            WHERE mg.movie_id IN (:movieIds)
            ORDER BY mg.movie_id, g.id
            """;

    private final EntityManager entityManager;

    /** 카드 후보를 화면에 노출할 순서 그대로 읽는다. */
    public List<OnboardingCardQueryResult> findCards(int size) {
        List<?> rows =
                entityManager
                        .createNativeQuery(SELECT_CARDS, Tuple.class)
                        .setParameter("size", size)
                        .getResultList();
        List<OnboardingCardQueryResult> cards = new ArrayList<>(rows.size());
        for (Object result : rows) {
            Tuple row = Tuple.class.cast(result);
            cards.add(
                    new OnboardingCardQueryResult(
                            row.get("movie_id", Number.class).longValue(),
                            row.get("title", String.class),
                            row.get("poster_url", String.class),
                            releaseYearOf(row),
                            row.get("flavor_code", String.class)));
        }
        return cards;
    }

    /**
     * 카드로 고른 영화들의 장르 이름을 영화별로 모은다.
     *
     * <p>카드가 최대 10장이라 {@code in} 절 크기가 입력으로 커지지 않는다. 장르가 없는 영화는 결과에 키가 생기지 않으므로 호출자가 빈 목록으로 다룬다.
     */
    public Map<Long, List<String>> findGenreNames(List<Long> movieIds) {
        if (movieIds.isEmpty()) {
            return Map.of();
        }
        List<?> rows =
                entityManager
                        .createNativeQuery(SELECT_GENRE_NAMES, Tuple.class)
                        .setParameter("movieIds", movieIds)
                        .getResultList();
        Map<Long, List<String>> genreNames = new LinkedHashMap<>();
        for (Object result : rows) {
            Tuple row = Tuple.class.cast(result);
            genreNames
                    .computeIfAbsent(
                            row.get("movie_id", Number.class).longValue(), key -> new ArrayList<>())
                    .add(row.get("name", String.class));
        }
        return genreNames;
    }

    private static Integer releaseYearOf(Tuple row) {
        Number releaseYear = row.get("release_year", Number.class);
        return releaseYear == null ? null : releaseYear.intValue();
    }
}
