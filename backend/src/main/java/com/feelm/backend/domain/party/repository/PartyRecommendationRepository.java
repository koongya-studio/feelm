package com.feelm.backend.domain.party.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    /** 현재 파티원 중 한 명이라도 평가·관심없음·확정 감상한 영화를 제외하는 조건. 별칭 {@code i}의 항목에 적용한다. */
    private static final String ELIGIBLE_FOR_MEMBERS =
            """
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
            """;

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

    /** 저장 순서는 {@code position}이 정하므로 INSERT 순서는 자유다. 반환 id도 movie_id 오름차순이다. */
    public List<Long> insertItems(long setId, List<NewItem> items) {
        // FK 검사가 movies 행에 KEY SHARE를 행 순서대로 건다. 온보딩(API-024)의 id 오름차순 FOR UPDATE와 같은 방향으로
        // 맞춰 다른 사용자 간 교착을 막는다(S15P21E106-804).
        List<NewItem> byMovieId =
                items.stream().sorted(Comparator.comparingLong(NewItem::movieId)).toList();
        List<Long> ids = new ArrayList<>(byMovieId.size());
        for (NewItem item : byMovieId) {
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

    /** 세트에서 가장 최근에 노출한 묶음의 시각. 노출 이력이 없으면 비어 있다. */
    public Optional<OffsetDateTime> findLatestExposedAt(long setId) {
        return Optional.ofNullable(
                jdbcTemplate.queryForObject(
                        "SELECT max(exposed_at) FROM recommendation_items"
                                + " WHERE set_id=? AND type='PARTY'",
                        OffsetDateTime.class,
                        setId));
    }

    /**
     * 현재 노출 중인 묶음(가장 최근 노출 시각과 같은 항목)을 저장된 그대로 돌려준다. 파티원의 평가·관심없음·감상 확정은 묶음을 새로 고를 때만 반영하므로 여기서는
     * 걸러내지 않는다.
     */
    public List<StoredItem> findCurrentBundle(long setId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT i.id,i.movie_id,i.type,
                       m.title,m.poster_url,m.release_date,m.average_rating
                FROM recommendation_items i
                JOIN movies m ON m.id=i.movie_id
                WHERE i.set_id=? AND i.type='PARTY'
                  AND i.exposed_at IS NOT NULL
                  AND i.exposed_at = (
                    SELECT max(exposed_at) FROM recommendation_items
                    WHERE set_id=? AND type='PARTY'
                  )
                ORDER BY i.position,i.id LIMIT ?
                """,
                this::mapItem,
                setId,
                setId,
                limit);
    }

    /** 아직 노출하지 않은 후보 중 현재 파티원 기준으로 유효한 항목을 순위대로 돌려준다. */
    public List<StoredItem> findUnexposedEligible(long setId, List<Long> userIds, int limit) {
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
                WHERE i.set_id=:setId AND i.type='PARTY' AND i.exposed_at IS NULL
                """
                        + ELIGIBLE_FOR_MEMBERS
                        + """
                ORDER BY i.position,i.id LIMIT :limit
                """,
                parameters,
                this::mapItem);
    }

    /** 다시 받기로 더 보여줄 유효한 미노출 후보가 남아 있는지. 응답의 {@code hasNext}다. */
    public boolean hasUnexposedEligible(long setId, List<Long> userIds) {
        MapSqlParameterSource parameters =
                new MapSqlParameterSource("setId", setId).addValue("userIds", userIds);
        Boolean exists =
                namedParameterJdbcTemplate.queryForObject(
                        """
                        SELECT EXISTS (
                          SELECT 1 FROM recommendation_items i
                          WHERE i.set_id=:setId AND i.type='PARTY' AND i.exposed_at IS NULL
                        """
                                + ELIGIBLE_FOR_MEMBERS
                                + """
                        )
                        """,
                        parameters,
                        Boolean.class);
        return Boolean.TRUE.equals(exists);
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
