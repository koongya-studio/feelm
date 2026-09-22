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
            literal_matches AS MATERIALIZED (
                SELECT m.id,
                    (CASE
                        WHEN movie_search_normalize(m.title) = movie_search_normalize(:query) THEN 7
                        WHEN movie_search_normalize(m.original_title) = movie_search_normalize(:query) THEN 6
                        WHEN movie_search_normalize(m.title) LIKE movie_search_normalize(:prefixPattern) ESCAPE '!' THEN 5
                        WHEN movie_search_normalize(m.original_title) LIKE movie_search_normalize(:prefixPattern) ESCAPE '!' THEN 4
                        WHEN movie_search_normalize(m.title) LIKE movie_search_normalize(:pattern) ESCAPE '!' THEN 3
                        WHEN movie_search_normalize(m.original_title) LIKE movie_search_normalize(:pattern) ESCAPE '!' THEN 2
                        ELSE 1
                    END)::numeric * 1000000000000000
                    + CASE
                        WHEN movie_search_normalize(m.title) = movie_search_normalize(:query)
                             OR movie_search_normalize(m.original_title) = movie_search_normalize(:query) THEN 0
                        WHEN series.title_number IS NOT NULL THEN (20 - series.title_number) * 1000000000000
                        WHEN movie_search_normalize(m.title) LIKE movie_search_normalize(:prefixPattern) ESCAPE '!' THEN 0
                        WHEN series.original_number IS NOT NULL THEN (20 - series.original_number) * 1000000000000
                        ELSE 0
                    END
                    + m.rating_count
                    + least(greatest(
                    similarity(movie_search_normalize(m.title), movie_search_normalize(:query)),
                    similarity(movie_search_normalize(m.original_title), movie_search_normalize(:query)),
                    ts_rank(to_tsvector('simple', coalesce(movie_search_normalize(m.title), '')),
                            plainto_tsquery('simple', movie_search_normalize(:query))),
                    ts_rank(to_tsvector('simple', coalesce(movie_search_normalize(m.original_title), '')),
                            plainto_tsquery('simple', movie_search_normalize(:query)))), 0.999999)::numeric AS score
                FROM movies m
                /*ELIGIBLE_JOIN*/
                CROSS JOIN LATERAL (
                    SELECT
                        CASE WHEN movie_search_normalize(m.title) LIKE movie_search_normalize(:seriesPattern) ESCAPE '!'
                             THEN substring(btrim(substring(movie_search_normalize(m.title)
                                      FROM char_length(movie_search_normalize(:query)) + 1))
                                      FROM '^([1-9]|1[0-9])([^0-9]|$)')::numeric END AS title_number,
                        CASE WHEN movie_search_normalize(m.original_title) LIKE movie_search_normalize(:seriesPattern) ESCAPE '!'
                             THEN substring(btrim(substring(movie_search_normalize(m.original_title)
                                      FROM char_length(movie_search_normalize(:query)) + 1))
                                      FROM '^([1-9]|1[0-9])([^0-9]|$)')::numeric END AS original_number
                ) series
                WHERE movie_search_normalize(m.title) LIKE movie_search_normalize(:pattern) ESCAPE '!'
                    OR movie_search_normalize(m.original_title) LIKE movie_search_normalize(:pattern) ESCAPE '!'
                UNION ALL
                SELECT mp.movie_id,
                    (CASE WHEN movie_search_normalize(p.name) = movie_search_normalize(:query)
                                   AND mp.role = 'DIRECTOR' THEN 5.5
                          WHEN movie_search_normalize(p.name) = movie_search_normalize(:query)
                                   THEN 5.25 ELSE 1 END)::numeric * 1000000000000000
                    + m.rating_count
                    + CASE WHEN movie_search_normalize(p.name) = movie_search_normalize(:query)
                           THEN coalesce(least(greatest((m.release_date - DATE '1800-01-01')::numeric
                                   / 1000000, 0), 0.999999), 0)
                           ELSE least(similarity(movie_search_normalize(p.name),
                                   movie_search_normalize(:query)), 0.999999)::numeric END AS score
                FROM people p JOIN movie_people mp ON mp.person_id = p.id
                JOIN movies m ON m.id = mp.movie_id
                /*ELIGIBLE_JOIN*/
                WHERE mp.role IN ('ACTOR', 'DIRECTOR')
                    AND movie_search_normalize(p.name) LIKE movie_search_normalize(:pattern) ESCAPE '!'
                /*RELAXED_LITERAL_MATCHES*/
            ), fulltext_matches AS MATERIALIZED (
                SELECT m.id, 1000000000000000::numeric
                    + m.rating_count
                    + least(greatest(
                        similarity(movie_search_normalize(m.title), movie_search_normalize(:query)),
                        similarity(movie_search_normalize(m.original_title), movie_search_normalize(:query)),
                        ts_rank(to_tsvector('simple', coalesce(movie_search_normalize(m.title), '')),
                                plainto_tsquery('simple', movie_search_normalize(:query))),
                        ts_rank(to_tsvector('simple', coalesce(movie_search_normalize(m.original_title), '')),
                                plainto_tsquery('simple', movie_search_normalize(:query)))), 0.999999)::numeric AS score
                FROM movies m
                /*ELIGIBLE_JOIN*/
                WHERE NOT EXISTS (SELECT 1 FROM literal_matches)
                    AND (to_tsvector('simple', coalesce(movie_search_normalize(m.title), ''))
                              @@ plainto_tsquery('simple', movie_search_normalize(:query))
                         OR to_tsvector('simple', coalesce(movie_search_normalize(m.original_title), ''))
                              @@ plainto_tsquery('simple', movie_search_normalize(:query)))
            ), keyword_matches AS (
                SELECT id, score FROM literal_matches
                UNION ALL
                SELECT id, score FROM fulltext_matches
                UNION ALL
                SELECT m.id, 1000000000000000::numeric
                    + m.rating_count
                    + least(greatest(
                        similarity(movie_search_normalize(m.title), movie_search_normalize(:query)),
                        similarity(movie_search_normalize(m.original_title), movie_search_normalize(:query))),
                        0.999999)::numeric AS score
                FROM movies m
                /*ELIGIBLE_JOIN*/
                WHERE NOT EXISTS (SELECT 1 FROM literal_matches)
                    AND NOT EXISTS (SELECT 1 FROM fulltext_matches)
                    AND (movie_search_normalize(m.title) % movie_search_normalize(:query)
                         OR movie_search_normalize(m.original_title) % movie_search_normalize(:query))
                UNION ALL
                SELECT mp.movie_id, 1000000000000000::numeric
                    + m.rating_count
                    + least(similarity(movie_search_normalize(p.name),
                                       movie_search_normalize(:query)), 0.999999)::numeric AS score
                FROM people p JOIN movie_people mp ON mp.person_id = p.id
                JOIN movies m ON m.id = mp.movie_id
                /*ELIGIBLE_JOIN*/
                WHERE NOT EXISTS (SELECT 1 FROM literal_matches)
                    AND NOT EXISTS (SELECT 1 FROM fulltext_matches)
                    AND mp.role IN ('ACTOR', 'DIRECTOR')
                    AND movie_search_normalize(p.name) % movie_search_normalize(:query)
                /*RELAXED_TYPO_MATCHES*/
            ), matches AS (
                SELECT id, cast(max(score) AS numeric) AS score
                FROM keyword_matches GROUP BY id
            ),
            """;

    private static final String RELAXED_SETTING =
            """
            relaxed_setting AS MATERIALIZED (
                SELECT set_config('pg_trgm.word_similarity_threshold', :wordSimilarityThreshold, true) AS threshold,
                       regexp_replace(movie_search_normalize(:query), '[[:space:]]+', '', 'g') AS compact_query
            ),
            """;

    private static final String RELAXED_LITERAL_MATCHES =
            """
                UNION ALL
                SELECT m.id,
                    (CASE
                        WHEN compact.title = cfg.compact_query THEN 5.9
                        WHEN compact.original_title = cfg.compact_query THEN 5.8
                        WHEN series.title_number IS NOT NULL THEN 5.7
                        WHEN series.original_number IS NOT NULL THEN 5.6
                        WHEN left(compact.title, char_length(cfg.compact_query)) = cfg.compact_query THEN 3.9
                        ELSE 3.8
                    END)::numeric * 1000000000000000
                    + coalesce((20 - series.title_number) * 1000000000000, 0)
                    + coalesce((20 - series.original_number) * 1000000000000, 0)
                    + m.rating_count
                    + least(word_similarity(movie_search_normalize(:query),
                                            movie_search_normalize(m.title)), 0.999999)::numeric AS score
                FROM relaxed_setting cfg CROSS JOIN movies m
                /*ELIGIBLE_JOIN*/
                CROSS JOIN LATERAL (
                    SELECT regexp_replace(movie_search_normalize(m.title), '[[:space:]]+', '', 'g') AS title,
                           regexp_replace(movie_search_normalize(m.original_title), '[[:space:]]+', '', 'g') AS original_title
                ) compact
                CROSS JOIN LATERAL (
                    SELECT CASE WHEN left(compact.title, char_length(cfg.compact_query)) = cfg.compact_query
                                THEN substring(substring(compact.title FROM char_length(cfg.compact_query) + 1)
                                               FROM '^([1-9]|1[0-9])([^0-9]|$)')::numeric END AS title_number,
                           CASE WHEN left(compact.original_title, char_length(cfg.compact_query)) = cfg.compact_query
                                THEN substring(substring(compact.original_title FROM char_length(cfg.compact_query) + 1)
                                               FROM '^([1-9]|1[0-9])([^0-9]|$)')::numeric END AS original_number
                ) series
                WHERE cfg.threshold IS NOT NULL
                    AND (cfg.compact_query <% movie_search_normalize(m.title)
                         OR cfg.compact_query <% movie_search_normalize(m.original_title))
                    AND (left(compact.title, char_length(cfg.compact_query)) = cfg.compact_query
                         OR left(compact.original_title, char_length(cfg.compact_query)) = cfg.compact_query)
                UNION ALL
                SELECT mp.movie_id,
                    (CASE WHEN mp.role = 'DIRECTOR' THEN 5.45 ELSE 5.2 END)::numeric
                    * 1000000000000000 + m.rating_count
                    + least(word_similarity(movie_search_normalize(:query),
                                            movie_search_normalize(p.name)), 0.999999)::numeric AS score
                FROM relaxed_setting cfg CROSS JOIN people p
                JOIN movie_people mp ON mp.person_id = p.id AND mp.role IN ('ACTOR', 'DIRECTOR')
                JOIN movies m ON m.id = mp.movie_id
                /*ELIGIBLE_JOIN*/
                CROSS JOIN LATERAL (
                    SELECT regexp_replace(movie_search_normalize(p.name), '[[:space:]]+', '', 'g') AS name
                ) compact
                WHERE cfg.threshold IS NOT NULL
                    AND cfg.compact_query <% movie_search_normalize(p.name)
                    AND compact.name = cfg.compact_query
            """;

    private static final String RELAXED_TYPO_MATCHES =
            """
                UNION ALL
                SELECT m.id,
                    (CASE WHEN edits.title = 1 THEN 1.9 ELSE 1.8 END)::numeric
                    * 1000000000000000 + m.rating_count
                    + least(word_similarity(movie_search_normalize(:query),
                                            movie_search_normalize(m.title)), 0.999999)::numeric AS score
                FROM relaxed_setting cfg CROSS JOIN movies m
                /*ELIGIBLE_JOIN*/
                CROSS JOIN LATERAL (
                    SELECT regexp_replace(movie_search_normalize(m.title), '[[:space:]]+', '', 'g') AS title,
                           regexp_replace(movie_search_normalize(m.original_title), '[[:space:]]+', '', 'g') AS original_title
                ) compact
                CROSS JOIN LATERAL (
                    SELECT CASE WHEN char_length(compact.title) = char_length(cfg.compact_query)
                                THEN (SELECT count(*) FROM unnest(string_to_array(compact.title, NULL),
                                            string_to_array(cfg.compact_query, NULL)) chars(candidate_char, query_char)
                                      WHERE candidate_char <> query_char) END AS title,
                           CASE WHEN char_length(compact.original_title) = char_length(cfg.compact_query)
                                THEN (SELECT count(*) FROM unnest(string_to_array(compact.original_title, NULL),
                                            string_to_array(cfg.compact_query, NULL)) chars(candidate_char, query_char)
                                      WHERE candidate_char <> query_char) END AS original_title
                ) edits
                WHERE cfg.threshold IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM literal_matches)
                    AND NOT EXISTS (SELECT 1 FROM fulltext_matches)
                    AND (cfg.compact_query <% movie_search_normalize(m.title)
                         OR cfg.compact_query <% movie_search_normalize(m.original_title))
                    AND (edits.title = 1 OR edits.original_title = 1)
                UNION ALL
                SELECT mp.movie_id, 900000000000000::numeric + m.rating_count
                    + least(word_similarity(movie_search_normalize(:query),
                                            movie_search_normalize(p.name)), 0.999999)::numeric AS score
                FROM relaxed_setting cfg CROSS JOIN people p
                JOIN movie_people mp ON mp.person_id = p.id AND mp.role IN ('ACTOR', 'DIRECTOR')
                JOIN movies m ON m.id = mp.movie_id
                /*ELIGIBLE_JOIN*/
                CROSS JOIN LATERAL (
                    SELECT regexp_replace(movie_search_normalize(p.name), '[[:space:]]+', '', 'g') AS name
                ) compact
                WHERE cfg.threshold IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM literal_matches)
                    AND NOT EXISTS (SELECT 1 FROM fulltext_matches)
                    AND cfg.compact_query <% movie_search_normalize(p.name)
                    AND char_length(compact.name) = char_length(cfg.compact_query)
                    AND (SELECT count(*) FROM unnest(string_to_array(compact.name, NULL),
                                string_to_array(cfg.compact_query, NULL)) chars(candidate_char, query_char)
                         WHERE candidate_char <> query_char) = 1
            """;

    // Lowercasing and Greek sigma folding cannot change a Hangul syllable. For one or two
    // Hangul characters, raw LIKE finds the same literal candidates without normalizing every
    // title and person name in the table. Relevance scores still use the normal path.
    private static final String SHORT_HANGUL_KEYWORD_MATCHES =
            KEYWORD_MATCHES
                    .replace(
                            "movie_search_normalize(m.title) LIKE movie_search_normalize(:pattern)",
                            "m.title LIKE :pattern")
                    .replace(
                            "movie_search_normalize(m.original_title) LIKE movie_search_normalize(:pattern)",
                            "m.original_title LIKE :pattern")
                    .replace(
                            "movie_search_normalize(p.name) LIKE movie_search_normalize(:pattern)",
                            "p.name LIKE :pattern")
                    .replace(
                            "movie_search_normalize(m.title) LIKE movie_search_normalize(:prefixPattern)",
                            "m.title LIKE :prefixPattern")
                    .replace(
                            "movie_search_normalize(m.original_title) LIKE movie_search_normalize(:prefixPattern)",
                            "m.original_title LIKE :prefixPattern")
                    .replace(
                            "movie_search_normalize(m.title) LIKE movie_search_normalize(:seriesPattern)",
                            "m.title LIKE :seriesPattern")
                    .replace(
                            "movie_search_normalize(m.original_title) LIKE movie_search_normalize(:seriesPattern)",
                            "m.original_title LIKE :seriesPattern")
                    .replace(
                            "movie_search_normalize(m.title) = movie_search_normalize(:query)",
                            "m.title = :query")
                    .replace(
                            "movie_search_normalize(m.original_title) = movie_search_normalize(:query)",
                            "m.original_title = :query")
                    .replace(
                            "movie_search_normalize(p.name) = movie_search_normalize(:query)",
                            "p.name = :query");

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
    private static final String SEARCH_HOME_RELEASED_FILTER =
            """
             AND m.release_date <= :releasedThrough
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
            MovieSearchCriteria criteria,
            MovieSearchCursor cursor,
            Long userId,
            LocalDate releasedThrough) {
        Map<String, Object> parameters = new HashMap<>();
        StringBuilder sql = new StringBuilder();
        boolean keyword = criteria.q() != null;
        boolean searchHomeTopRated = releasedThrough != null;
        long hangulCount =
                keyword
                        ? criteria.q()
                                .codePoints()
                                .filter(point -> point >= 0xAC00 && point <= 0xD7A3)
                                .count()
                        : 0;
        boolean relaxed = hangulCount >= 3;
        sql.append("WITH ");
        if (keyword) {
            boolean filtered =
                    !criteria.genre().isEmpty()
                            || !criteria.country().isEmpty()
                            || !criteria.ott().isEmpty()
                            || criteria.yearFrom() != null
                            || criteria.yearTo() != null;
            if (filtered) {
                // Candidate fallback must consider only movies eligible for this filter set.
                sql.append("eligible_movies AS (SELECT m.id FROM movies m WHERE true ");
                appendFilters(sql, parameters, criteria);
                sql.append("), ");
            }
            if (relaxed) {
                sql.append(RELAXED_SETTING);
                parameters.put("wordSimilarityThreshold", hangulCount >= 5 ? "0.14" : "0.25");
            }
            String keywordSql =
                    criteria.q().matches("[가-힣]{1,2}")
                            ? SHORT_HANGUL_KEYWORD_MATCHES
                            : KEYWORD_MATCHES;
            sql.append(
                    keywordSql
                            .replace(
                                    "/*RELAXED_LITERAL_MATCHES*/",
                                    relaxed ? RELAXED_LITERAL_MATCHES : "")
                            .replace(
                                    "/*RELAXED_TYPO_MATCHES*/", relaxed ? RELAXED_TYPO_MATCHES : "")
                            .replace(
                                    "/*ELIGIBLE_JOIN*/",
                                    filtered ? "JOIN eligible_movies em ON em.id = m.id" : ""));
            parameters.put("query", criteria.q());
            parameters.put(
                    "pattern",
                    "%"
                            + criteria.q().replace("!", "!!").replace("%", "!%").replace("_", "!_")
                            + "%");
            parameters.put(
                    "prefixPattern",
                    criteria.q().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            parameters.put(
                    "seriesPattern",
                    criteria.q().replace("!", "!!").replace("%", "!%").replace("_", "!_") + " %");
        }
        sql.append("ranked AS (SELECT m.id, m.title, m.original_title, m.poster_url, ")
                .append("m.release_date, m.average_rating, m.rating_count, ");
        if (criteria.sort() == MovieSearchSort.RELEASE_DATE) {
            sql.append("cast(NULL AS numeric)");
        } else if (criteria.sort() == MovieSearchSort.RELEVANCE && keyword) {
            // Language and MovieLens signals stay below title/series bands and above rating-count
            // ties.
            // After -615, a verified Korean display title is stored in movies.title.
            sql.append(
                    "(matches.score"
                            + " + CASE WHEN m.movielens_id IS NOT NULL THEN 100000000000 ELSE 0 END"
                            + " + CASE WHEN m.title ~ '[가-힣]' THEN 100000000000 ELSE 0 END"
                            + " - CASE WHEN m.title ~ '[ء-ي]' THEN 400000000000 ELSE 0 END)");
        } else {
            sql.append("m.average_rating");
        }
        sql.append(" AS sort_value, count(*) OVER() AS total_count FROM movies m ");
        if (keyword) {
            sql.append("JOIN matches ON matches.id = m.id ");
        }
        sql.append("WHERE true ");
        appendFilters(sql, parameters, criteria);
        if (searchHomeTopRated) {
            sql.append(SEARCH_HOME_RELEASED_FILTER);
            parameters.put("releasedThrough", releasedThrough);
        }
        sql.append(") ");
        String rankedSql = sql.toString();
        Map<String, Object> countParameters = cursor == null ? Map.of() : new HashMap<>(parameters);
        sql.append("SELECT r.*, ")
                .append(userId == null ? "false" : WATCHED)
                .append(" AS watched FROM ranked r WHERE true ");
        if (userId != null) {
            parameters.put("userId", userId);
        }
        appendCursor(sql, parameters, criteria.sort(), searchHomeTopRated, cursor);
        if (criteria.sort() == MovieSearchSort.RELEASE_DATE) {
            sql.append(" ORDER BY r.release_date DESC NULLS LAST, r.id DESC");
        } else if (searchHomeTopRated) {
            sql.append(" ORDER BY r.sort_value DESC, r.rating_count DESC, r.id DESC");
        } else {
            sql.append(" ORDER BY r.sort_value DESC, r.id DESC");
        }

        var query = entityManager.createNativeQuery(sql.toString(), Tuple.class);
        parameters.forEach(query::setParameter);
        List<?> result = query.setMaxResults(criteria.size() + 1).getResultList();
        List<Tuple> rows = result.stream().map(Tuple.class::cast).toList();
        if (rows.isEmpty()) {
            if (cursor == null) {
                return new MovieSearchQueryResult(List.of(), 0L);
            }
            var countQuery =
                    entityManager.createNativeQuery(rankedSql + "SELECT count(*) FROM ranked");
            countParameters.forEach(countQuery::setParameter);
            long totalCount = ((Number) countQuery.getSingleResult()).longValue();
            return new MovieSearchQueryResult(List.of(), totalCount);
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
            boolean searchHomeTopRated,
            MovieSearchCursor cursor) {
        if (cursor == null) {
            return;
        }
        parameters.put("lastId", cursor.movieId());
        if (searchHomeTopRated) {
            sql.append(
                    """
                     AND (r.sort_value, r.rating_count, r.id) <
                         (:lastValue,
                          (SELECT anchor.rating_count FROM movies anchor WHERE anchor.id = :lastId),
                          :lastId)
                    """);
            parameters.put("lastValue", cursor.sortValue());
        } else if (sort != MovieSearchSort.RELEASE_DATE) {
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
