package com.feelm.backend.domain.trend.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 급상승 목록에 붙일 영화 표시 정보만 읽는다. 순위는 Redis 스냅샷이 정하므로 정렬 기준을 두지 않는다.
 *
 * <p>트랜잭션 없이 조회한다. 호출 측이 Redis를 먼저 읽으므로 트랜잭션을 열면 외부 호출을 감싸게 된다(선례 API-068).
 */
@Repository
@RequiredArgsConstructor
public class TrendingMovieRepository {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 주어진 영화의 제목·포스터와 평가 집계를 {@code movieId}로 찾을 수 있게 돌려준다.
     *
     * <p>적재되지 않은 {@code movieId}는 결과에 없다. 호출자가 그 항목을 응답에서 제외한다.
     */
    public Map<Long, TrendingMovieMetadata> findMovies(Collection<Long> movieIds) {
        if (movieIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, TrendingMovieMetadata> movies = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT m.id, m.title, m.poster_url, m.average_rating, m.rating_count
                FROM movies m
                WHERE m.id IN (:movieIds)
                """,
                new MapSqlParameterSource("movieIds", movieIds),
                resultSet -> {
                    long movieId = resultSet.getLong("id");
                    movies.put(
                            movieId,
                            new TrendingMovieMetadata(
                                    movieId,
                                    resultSet.getString("title"),
                                    resultSet.getString("poster_url"),
                                    resultSet.getBigDecimal("average_rating"),
                                    resultSet.getInt("rating_count")));
                });
        return movies;
    }

    public record TrendingMovieMetadata(
            long movieId,
            String title,
            String posterUrl,
            BigDecimal averageRating,
            int ratingCount) {}
}
