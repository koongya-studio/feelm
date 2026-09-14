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
 * <p>{@code movies}에는 Entity가 없고(콘텐츠 도메인이 적재 경로에서 직접 다룬다) 맛 균등 배분에 윈도 함수가 필요해 Native Query를 쓴다(컨벤션
 * 9장). 이 도메인은 {@code movies}·{@code movie_genres}·{@code genres}·{@code popcorn_flavors}를 읽기만 하고 쓰지
 * 않는다.
 */
@Repository
@RequiredArgsConstructor
public class OnboardingCardRepository {

    /**
     * 맛별 상위 후보를 라운드로빈으로 섞어 카드를 고른다.
     *
     * <p>LATERAL이 맛마다 상위 {@code size}편만 남기므로 윈도 함수가 도는 대상은 최대 (맛 수 × size)행이다. 전체 {@code movies}에
     * 윈도를 돌리지 않기 위한 구성이다. {@code flavor_rank}를 먼저 정렬해 1순위를 맛마다 한 장씩 깔고 그다음 순위로 넘어가므로, 한 맛의 후보가 모자라면
     * 남은 자리를 다른 맛의 다음 순위가 채운다.
     *
     * <p>인기도 정렬은 {@code rating_count DESC, average_rating DESC, id ASC}다. 명세가 가정한 MovieLens 평가 수 열이
     * 아직 {@code movies}에 없어 서비스 평가 수를 쓰며, 같은 요청이 같은 순서를 돌려주도록 {@code id}로 동점을 끊는다. 열이 생기면 이 SQL의
     * 정렬식만 바꾸면 된다.
     *
     * <p>대표 맛이 배정되지 않은 영화({@code representative_flavor_id IS NULL})는 조인에서 빠져 카드가 되지 않는다. 응답의 {@code
     * flavorCode}가 비워 둘 수 없는 값이기 때문이다.
     */
    private static final String SELECT_CARDS =
            """
            WITH flavor_top AS (
                SELECT f.code AS flavor_code, f.sort_order AS sort_order, top.id AS movie_id,
                       top.title AS title, top.poster_url AS poster_url,
                       cast(extract(year from top.release_date) AS int) AS release_year,
                       row_number() OVER (
                           PARTITION BY f.id
                           ORDER BY top.rating_count DESC, top.average_rating DESC, top.id
                       ) AS flavor_rank
                FROM popcorn_flavors f
                CROSS JOIN LATERAL (
                    SELECT m.id, m.title, m.poster_url, m.release_date,
                           m.rating_count, m.average_rating
                    FROM movies m
                    WHERE m.representative_flavor_id = f.id
                    ORDER BY m.rating_count DESC, m.average_rating DESC, m.id
                    LIMIT :size
                ) top
            )
            SELECT movie_id, title, poster_url, release_year, flavor_code
            FROM flavor_top
            ORDER BY flavor_rank, sort_order
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
     * <p>카드가 최대 40장이라 {@code in} 절 크기가 입력으로 커지지 않는다. 장르가 없는 영화는 결과에 키가 생기지 않으므로 호출자가 빈 목록으로 다룬다.
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
