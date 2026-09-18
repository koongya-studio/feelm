package com.feelm.backend.domain.party.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 파티 추천 후보 필터와 세트·아이템 이력 저장을 담당한다. */
@Repository
@RequiredArgsConstructor
public class PartyRecommendationRepository {
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public Set<Long> findEligibleMovieIds(List<Long> userIds, List<Long> movieIds) {
        if (userIds.isEmpty() || movieIds.isEmpty()) {
            return Set.of();
        }
        MapSqlParameterSource parameters =
                new MapSqlParameterSource("userIds", userIds).addValue("movieIds", movieIds);
        return Set.copyOf(
                namedParameterJdbcTemplate.queryForList(
                        """
                        SELECT m.id
                        FROM movies m
                        WHERE m.id IN (:movieIds)
                          AND NOT EXISTS (
                            SELECT 1 FROM ratings r
                            WHERE r.user_id IN (:userIds) AND r.movie_id = m.id
                          )
                          AND NOT EXISTS (
                            SELECT 1 FROM user_movie_dismissals d
                            WHERE d.user_id IN (:userIds) AND d.movie_id = m.id
                          )
                          AND NOT EXISTS (
                            SELECT 1 FROM watch_records w
                            WHERE w.user_id IN (:userIds) AND w.movie_id = m.id
                              AND w.status IN ('WATCHED_UNRATED', 'RATED')
                          )
                        """,
                        parameters,
                        Long.class));
    }

    public Optional<RecommendationSet> findExistingSet(
            long partyId, String inputId, String modelVersion) {
        return jdbcTemplate
                .query(
                        "SELECT id,generated_at FROM recommendation_sets"
                                + " WHERE party_id=? AND user_id IS NULL"
                                + " AND input_version=? AND model_version=?",
                        (resultSet, rowNumber) ->
                                new RecommendationSet(
                                        resultSet.getLong("id"),
                                        resultSet.getObject("generated_at", OffsetDateTime.class)),
                        partyId,
                        inputId,
                        modelVersion)
                .stream()
                .findFirst();
    }

    public long insertSet(
            long partyId, String inputId, String modelVersion, OffsetDateTime generatedAt) {
        Long id =
                jdbcTemplate.queryForObject(
                        "INSERT INTO recommendation_sets"
                                + " (party_id,input_version,model_version,candidate_version,generated_at)"
                                + " VALUES (?,?,?,0,?) RETURNING id",
                        Long.class,
                        partyId,
                        inputId,
                        modelVersion,
                        generatedAt);
        if (id == null) {
            throw new IllegalStateException("Party recommendation set ID was not generated");
        }
        return id;
    }

    public List<Long> insertItems(long setId, List<NewItem> items) {
        List<Long> ids = new ArrayList<>(items.size());
        for (NewItem item : items) {
            Long id =
                    jdbcTemplate.queryForObject(
                            "INSERT INTO recommendation_items"
                                    + " (set_id,movie_id,type,position,reason)"
                                    + " VALUES (?,?,'PARTY',?,CAST(? AS jsonb)) RETURNING id",
                            Long.class,
                            setId,
                            item.movieId(),
                            item.position(),
                            "{}");
            if (id == null) {
                throw new IllegalStateException("Party recommendation item ID was not generated");
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    public List<StoredItem> findItems(long setId, List<Long> userIds, int limit) {
        MapSqlParameterSource parameters =
                new MapSqlParameterSource("setId", setId)
                        .addValue("userIds", userIds)
                        .addValue("limit", limit);
        return namedParameterJdbcTemplate.query(
                """
                SELECT i.id,i.movie_id,i.type,
                       m.title,m.poster_url,m.release_date,m.average_rating
                FROM recommendation_items i
                JOIN movies m ON m.id=i.movie_id
                WHERE i.set_id=:setId AND i.type='PARTY'
                  AND NOT EXISTS (
                    SELECT 1 FROM ratings r
                    WHERE r.user_id IN (:userIds) AND r.movie_id=i.movie_id
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM user_movie_dismissals d
                    WHERE d.user_id IN (:userIds) AND d.movie_id=i.movie_id
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM watch_records w
                    WHERE w.user_id IN (:userIds) AND w.movie_id=i.movie_id
                      AND w.status IN ('WATCHED_UNRATED', 'RATED')
                  )
                ORDER BY i.position,i.id LIMIT :limit
                """,
                parameters,
                this::mapItem);
    }

    public void expose(List<StoredItem> items, OffsetDateTime exposedAt) {
        if (items.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "UPDATE recommendation_items SET exposed_at=COALESCE(exposed_at,?) WHERE id=?",
                items,
                items.size(),
                (statement, item) -> {
                    statement.setObject(1, exposedAt);
                    statement.setLong(2, item.id());
                });
    }

    public Map<Long, List<String>> findGenreNames(List<Long> movieIds) {
        return groupedStrings(
                "SELECT mg.movie_id,g.name AS value"
                        + " FROM movie_genres mg JOIN genres g ON g.id=mg.genre_id"
                        + " WHERE mg.movie_id IN (%s) ORDER BY mg.movie_id,g.name,g.id",
                movieIds);
    }

    public Map<Long, List<String>> findOttCodes(List<Long> movieIds) {
        return groupedStrings(
                "SELECT mop.movie_id,o.code AS value"
                        + " FROM movie_ott_providers mop JOIN otts o ON o.id=mop.ott_id"
                        + " WHERE mop.movie_id IN (%s) ORDER BY mop.movie_id,o.code,o.id",
                movieIds);
    }

    private Map<Long, List<String>> groupedStrings(String sql, List<Long> movieIds) {
        if (movieIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(movieIds.size(), "?"));
        Map<Long, List<String>> grouped = new LinkedHashMap<>();
        jdbcTemplate.query(
                sql.formatted(placeholders),
                (org.springframework.jdbc.core.RowCallbackHandler)
                        resultSet ->
                                grouped.computeIfAbsent(
                                                resultSet.getLong("movie_id"),
                                                ignored -> new ArrayList<>())
                                        .add(resultSet.getString("value")),
                movieIds.toArray());
        return grouped;
    }

    private StoredItem mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
        Date releaseDate = resultSet.getDate("release_date");
        return new StoredItem(
                resultSet.getLong("id"),
                resultSet.getLong("movie_id"),
                resultSet.getString("type"),
                resultSet.getString("title"),
                resultSet.getString("poster_url"),
                releaseDate == null ? null : releaseDate.toLocalDate().getYear(),
                resultSet.getBigDecimal("average_rating"));
    }

    public record RecommendationSet(long id, OffsetDateTime generatedAt) {}

    public record NewItem(long movieId, int position) {}

    public record StoredItem(
            long id,
            long movieId,
            String type,
            String title,
            String posterUrl,
            Integer releaseYear,
            BigDecimal averageRating) {}
}
