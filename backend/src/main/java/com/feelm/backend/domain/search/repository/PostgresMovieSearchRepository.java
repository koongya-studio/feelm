package com.feelm.backend.domain.search.repository;

import com.feelm.backend.domain.search.cursor.MovieSearchCursor;
import com.feelm.backend.domain.search.dto.request.MovieSearchCriteria;
import com.feelm.backend.domain.search.dto.request.MovieSearchSort;
import com.feelm.backend.domain.search.dto.response.MovieSearchItemResponse;
import com.feelm.backend.domain.search.dto.response.MovieSearchQueryResult;
import com.feelm.backend.domain.search.dto.response.MovieSearchResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** PostgreSQL full-text·pg_trgm 검색. SQL 구조는 상수로 조합하고 모든 입력값은 바인딩한다. */
@Repository
@RequiredArgsConstructor
public class PostgresMovieSearchRepository implements MovieSearchRepository {

    private static final String KEYWORD_MATCHES =
            """
            WITH keyword_matches AS (
                SELECT m.id, greatest(
                    similarity(movie_search_normalize(m.title), movie_search_normalize(:query)),
                    similarity(movie_search_normalize(m.original_title), movie_search_normalize(:query)),
                    ts_rank(to_tsvector('simple', coalesce(movie_search_normalize(m.title), '')),
                            plainto_tsquery('simple', movie_search_normalize(:query))),
                    ts_rank(to_tsvector('simple', coalesce(movie_search_normalize(m.original_title), '')),
                            plainto_tsquery('simple', movie_search_normalize(:query)))) AS score
                FROM movies m
                WHERE to_tsvector('simple', coalesce(movie_search_normalize(m.title), ''))
                          @@ plainto_tsquery('simple', movie_search_normalize(:query))
                    OR to_tsvector('simple', coalesce(movie_search_normalize(m.original_title), ''))
                          @@ plainto_tsquery('simple', movie_search_normalize(:query))
                    OR movie_search_normalize(m.title) LIKE movie_search_normalize(:pattern) ESCAPE '!'
                    OR movie_search_normalize(m.original_title) LIKE movie_search_normalize(:pattern) ESCAPE '!'
                    OR movie_search_normalize(m.title) % movie_search_normalize(:query)
                    OR movie_search_normalize(m.original_title) % movie_search_normalize(:query)
                UNION ALL
                SELECT mp.movie_id, similarity(movie_search_normalize(p.name), movie_search_normalize(:query)) AS score
                FROM people p JOIN movie_people mp ON mp.person_id = p.id
                WHERE mp.role IN ('ACTOR', 'DIRECTOR')
                    AND (movie_search_normalize(p.name) LIKE movie_search_normalize(:pattern) ESCAPE '!'
                         OR movie_search_normalize(p.name) % movie_search_normalize(:query))
            ), matches AS (
                SELECT id, cast(max(score) AS numeric) AS score
                FROM keyword_matches GROUP BY id
            ),
            """;

    private static final String GENRE_FILTER =
            """
             AND EXISTS (SELECT 1 FROM movie_genres mg JOIN genres g ON g.id = mg.genre_id
                         WHERE mg.movie_id = m.id AND g.code IN (:genres))
            """;
    private static final String COUNTRY_FILTER =
            """
             AND EXISTS (SELECT 1 FROM movie_countries mc
                         WHERE mc.movie_id = m.id AND mc.country_code IN (:countries))
            """;
    private static final String OTT_FILTER =
            """
             AND EXISTS (SELECT 1 FROM movie_ott_providers mo
                         WHERE mo.movie_id = m.id AND mo.ott_id IN (:otts))
            """;
    private static final String WATCHED =
            """
            EXISTS (SELECT 1 FROM watch_records wr
                    WHERE wr.movie_id = r.id AND wr.user_id = :userId
                      AND wr.status IN ('WATCHED_UNRATED', 'RATED'))
            """;
    private static final String GENRES =
            """
            SELECT DISTINCT mg.movie_id, g.name AS value
            FROM movie_genres mg JOIN genres g ON g.id = mg.genre_id
            WHERE mg.movie_id IN (:movieIds) ORDER BY mg.movie_id, g.name
            """;
    private static final String OTT_PROVIDERS =
            """
            SELECT mo.movie_id, o.code AS value
            FROM movie_ott_providers mo JOIN otts o ON o.id = mo.ott_id
            WHERE mo.movie_id IN (:movieIds) ORDER BY mo.movie_id, o.code
            """;

    private final EntityManager entityManager;

    @Override
    public MovieSearchQueryResult search(
            MovieSearchCriteria criteria, MovieSearchCursor cursor, Long userId) {
        Map<String, Object> parameters = new HashMap<>();
        StringBuilder sql = new StringBuilder();
        boolean keyword = criteria.q() != null;
        if (keyword) {
            sql.append(KEYWORD_MATCHES);
            parameters.put("query", criteria.q());
            parameters.put(
                    "pattern",
                    "%"
                            + criteria.q().replace("!", "!!").replace("%", "!%").replace("_", "!_")
                            + "%");
        } else {
            sql.append("WITH ");
        }
        sql.append("ranked AS (SELECT m.id, m.title, m.original_title, m.poster_url, ")
                .append("m.release_date, m.average_rating, ");
        if (criteria.sort() == MovieSearchSort.RELEASE_DATE) {
            sql.append("cast(NULL AS numeric)");
        } else if (criteria.sort() == MovieSearchSort.RELEVANCE && keyword) {
            sql.append("matches.score");
        } else {
            sql.append("m.average_rating");
        }
        sql.append(" AS sort_value, count(*) OVER() AS total_count FROM movies m ");
        if (keyword) {
            sql.append("JOIN matches ON matches.id = m.id ");
        }
        sql.append("WHERE true ");
        appendFilters(sql, parameters, criteria);
        sql.append(") SELECT r.*, ")
                .append(userId == null ? "false" : WATCHED)
                .append(" AS watched FROM ranked r WHERE true ");
        if (userId != null) {
            parameters.put("userId", userId);
        }
        appendCursor(sql, parameters, criteria.sort(), cursor);
        sql.append(
                criteria.sort() == MovieSearchSort.RELEASE_DATE
                        ? " ORDER BY r.release_date DESC NULLS LAST, r.id DESC"
                        : " ORDER BY r.sort_value DESC, r.id DESC");

        var query = entityManager.createNativeQuery(sql.toString(), Tuple.class);
        parameters.forEach(query::setParameter);
        List<?> result = query.setMaxResults(criteria.size() + 1).getResultList();
        List<Tuple> rows = result.stream().map(Tuple.class::cast).toList();
        if (rows.isEmpty()) {
            return new MovieSearchQueryResult(List.of(), 0L);
        }
        long totalCount = ((Number) rows.getFirst().get("total_count")).longValue();
        List<Long> movieIds =
                rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        Map<Long, List<String>> genres = collections(GENRES, movieIds);
        Map<Long, List<String>> ottProviders = collections(OTT_PROVIDERS, movieIds);
        return new MovieSearchQueryResult(
                rows.stream().map(row -> toResponse(row, genres, ottProviders)).toList(),
                totalCount);
    }

    private static void appendFilters(
            StringBuilder sql, Map<String, Object> parameters, MovieSearchCriteria criteria) {
        if (!criteria.genre().isEmpty()) {
            sql.append(GENRE_FILTER);
            parameters.put("genres", criteria.genre());
        }
        if (!criteria.country().isEmpty()) {
            sql.append(COUNTRY_FILTER);
            parameters.put("countries", criteria.country());
        }
        if (!criteria.ott().isEmpty()) {
            sql.append(OTT_FILTER);
            parameters.put("otts", criteria.ott());
        }
        if (criteria.yearFrom() != null) {
            sql.append(" AND extract(year FROM m.release_date) >= :yearFrom ");
            parameters.put("yearFrom", criteria.yearFrom());
        }
        if (criteria.yearTo() != null) {
            sql.append(" AND extract(year FROM m.release_date) <= :yearTo ");
            parameters.put("yearTo", criteria.yearTo());
        }
    }

    private static void appendCursor(
            StringBuilder sql,
            Map<String, Object> parameters,
            MovieSearchSort sort,
            MovieSearchCursor cursor) {
        if (cursor == null) {
            return;
        }
        parameters.put("lastId", cursor.movieId());
        if (sort != MovieSearchSort.RELEASE_DATE) {
            sql.append(
                    " AND (r.sort_value < :lastValue OR (r.sort_value = :lastValue AND r.id < :lastId)) ");
            parameters.put("lastValue", cursor.sortValue());
        } else if (cursor.releaseDate() == null) {
            sql.append(" AND r.release_date IS NULL AND r.id < :lastId ");
        } else {
            sql.append(
                    """
                     AND (r.release_date < :lastDate OR r.release_date IS NULL
                          OR (r.release_date = :lastDate AND r.id < :lastId))
                    """);
            parameters.put("lastDate", cursor.releaseDate());
        }
    }

    private Map<Long, List<String>> collections(String sql, List<Long> movieIds) {
        List<?> rows =
                entityManager
                        .createNativeQuery(sql, Tuple.class)
                        .setParameter("movieIds", movieIds)
                        .getResultList();
        Map<Long, List<String>> collections = new HashMap<>();
        for (Object result : rows) {
            Tuple row = Tuple.class.cast(result);
            long movieId = ((Number) row.get("movie_id")).longValue();
            collections
                    .computeIfAbsent(movieId, ignored -> new ArrayList<>())
                    .add(row.get("value", String.class));
        }
        return collections;
    }

    private static MovieSearchResponse toResponse(
            Tuple row, Map<Long, List<String>> genres, Map<Long, List<String>> ottProviders) {
        long movieId = ((Number) row.get("id")).longValue();
        Object date = row.get("release_date");
        LocalDate releaseDate =
                date instanceof java.sql.Date sqlDate ? sqlDate.toLocalDate() : (LocalDate) date;
        MovieSearchItemResponse item =
                new MovieSearchItemResponse(
                        movieId,
                        row.get("title", String.class),
                        row.get("original_title", String.class),
                        row.get("poster_url", String.class),
                        releaseDate == null ? null : releaseDate.getYear(),
                        List.copyOf(genres.getOrDefault(movieId, List.of())),
                        row.get("average_rating", BigDecimal.class),
                        List.copyOf(ottProviders.getOrDefault(movieId, List.of())),
                        row.get("watched", Boolean.class));
        return new MovieSearchResponse(item, row.get("sort_value", BigDecimal.class), releaseDate);
    }
}
