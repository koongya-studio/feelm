package com.feelm.backend.domain.search.repository;

import com.feelm.backend.domain.search.MovieFilterOptionsOttPolicy;
import com.feelm.backend.domain.search.dto.response.MovieFilterOptionsResponse.Option;
import com.feelm.backend.domain.search.dto.response.MovieFilterOptionsResponse.OttOption;
import com.feelm.backend.domain.search.dto.response.MovieFilterOptionsResponse.YearRange;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * API-028과 같은 EntityManager native 조회 기반을 사용한다.
 *
 * <p>엔티티 그래프와 영화별 연결 행 전체를 적재하지 않고 EXISTS·DISTINCT·연도 집계를 DB에서 수행한다. 연결 테이블의 FK가 영화 존재를 보장한다. 현행
 * 스키마에는 장르·영화·OTT 활성화 컬럼이 없다.
 */
@Repository
@RequiredArgsConstructor
public class MovieFilterOptionsRepository {

    private static final String GENRES =
            """
            SELECT g.code, g.name FROM genres g
            WHERE EXISTS (SELECT 1 FROM movie_genres mg WHERE mg.genre_id = g.id)
            ORDER BY g.code ASC
            """;

    private static final String COUNTRIES =
            """
            SELECT DISTINCT mc.country_code FROM movie_countries mc
            ORDER BY mc.country_code ASC
            """;

    private static final String YEAR_RANGE =
            """
            SELECT CAST(EXTRACT(YEAR FROM MIN(m.release_date)) AS integer) AS min_year,
                   CAST(EXTRACT(YEAR FROM MAX(m.release_date)) AS integer) AS max_year
            FROM movies m WHERE m.release_date IS NOT NULL
            """;

    private static final String OTTS =
            """
            SELECT o.id, o.code, o.name FROM otts o
            WHERE o.code IN (:supportedCodes)
              AND EXISTS (SELECT 1 FROM movie_ott_providers mo WHERE mo.ott_id = o.id)
            ORDER BY o.code ASC
            """;

    private final EntityManager entityManager;

    public List<Option> findGenres() {
        return rows(GENRES).stream()
                .map(
                        row ->
                                new Option(
                                        row.get("code", String.class),
                                        row.get("name", String.class)))
                .toList();
    }

    public List<String> findCountryCodes() {
        return rows(COUNTRIES).stream().map(row -> row.get("country_code", String.class)).toList();
    }

    public YearRange findYearRange() {
        Tuple row =
                (Tuple) entityManager.createNativeQuery(YEAR_RANGE, Tuple.class).getSingleResult();
        return new YearRange(year(row.get("min_year")), year(row.get("max_year")));
    }

    public List<OttOption> findOtts() {
        List<?> rows =
                entityManager
                        .createNativeQuery(OTTS, Tuple.class)
                        .setParameter(
                                "supportedCodes", MovieFilterOptionsOttPolicy.supportedCodes())
                        .getResultList();
        return rows.stream()
                .map(Tuple.class::cast)
                .map(
                        row ->
                                new OttOption(
                                        ((Number) row.get("id")).longValue(),
                                        row.get("code", String.class),
                                        row.get("name", String.class)))
                .toList();
    }

    private List<Tuple> rows(String sql) {
        List<?> rows = entityManager.createNativeQuery(sql, Tuple.class).getResultList();
        return rows.stream().map(Tuple.class::cast).toList();
    }

    private static Integer year(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }
}
