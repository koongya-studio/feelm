package com.feelm.backend.domain.recommendation.repository;

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
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecommendationServingRepository {

    private static final int TASTE_SIZE = 2;
    private static final int DISCOVERY_SIZE = 1;

    private final JdbcTemplate jdbcTemplate;

    public boolean lockActiveUserIfExists(long userId) {
        return !jdbcTemplate
                .queryForList(
                        "SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR NO KEY UPDATE",
                        Long.class,
                        userId)
                .isEmpty();
    }

    public Optional<RecommendationSet> findLatestSet(long userId) {
        return jdbcTemplate
                .query(
                        "SELECT id,generated_at FROM recommendation_sets"
                                + " WHERE user_id=? AND party_id IS NULL"
                                + " ORDER BY id DESC LIMIT 1",
                        (resultSet, rowNumber) -> mapSet(resultSet),
                        userId)
                .stream()
                .findFirst();
    }

    public List<ServingItem> findInitialVisibleItems(long userId, long setId) {
        List<ServingItem> discovery = findExposed(userId, setId, "DISCOVERY", DISCOVERY_SIZE);
        int tasteSize = discovery.isEmpty() ? TASTE_SIZE + DISCOVERY_SIZE : TASTE_SIZE;
        List<ServingItem> taste = findExposed(userId, setId, "TASTE", tasteSize);
        return ordered(taste, discovery);
    }

    /**
     * 세트에 서빙할 수 있는 유효 카드가 하나라도 남아 있는지 확인한다.
     *
     * <p>노출 여부를 가리지 않고 {@link #selectItems}와 같은 조인·술어를 쓴다. 판정과 서빙이 다른 기준을 쓰면 노출 쿼리가 집지 못하는 후보를 "남아
     * 있다"고 세어 빈 응답이 고착된다(S15P21E106-219).
     *
     * <p>이 값이 거짓인 것이 소진의 정의다. 소진 시점에는 노출된 유효 카드도 0편이므로 다음 회차를 만들어도 화면에서 사라지는 카드가 없다.
     */
    public boolean hasValidItems(long userId, long setId) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM recommendation_items i"
                                + " JOIN movies m ON m.id=i.movie_id"
                                + " WHERE i.set_id=? AND i.type IN ('TASTE','DISCOVERY')"
                                + validCandidateClause(),
                        Integer.class,
                        setId,
                        userId,
                        userId);
        return count != null && count > 0;
    }

    public boolean hasExposedItems(long setId) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM recommendation_items"
                                + " WHERE set_id=? AND exposed_at IS NOT NULL"
                                + " AND type IN ('TASTE','DISCOVERY')",
                        Integer.class,
                        setId);
        return count != null && count > 0;
    }

    public List<ServingItem> findAndLockInitialItems(long userId, long setId) {
        List<ServingItem> discovery =
                findUnexposedForUpdate(userId, setId, "DISCOVERY", DISCOVERY_SIZE);
        int tasteSize = discovery.isEmpty() ? TASTE_SIZE + DISCOVERY_SIZE : TASTE_SIZE;
        List<ServingItem> taste = findUnexposedForUpdate(userId, setId, "TASTE", tasteSize);
        // 목표 구성을 못 채워도 확보한 만큼 노출한다. 부족분은 보충·소진 흐름이 이어받는다(S15P21E106-219).
        return ordered(taste, discovery);
    }

    private List<ServingItem> ordered(List<ServingItem> taste, List<ServingItem> discovery) {
        List<ServingItem> items = new ArrayList<>(taste.size() + discovery.size());
        items.addAll(taste);
        items.addAll(discovery);
        return List.copyOf(items);
    }

    public Optional<ServingItem> findAndLockNextItem(long userId, long setId, String type) {
        return findUnexposedForUpdate(userId, setId, type, 1).stream().findFirst();
    }

    private List<ServingItem> findExposed(long userId, long setId, String type, int limit) {
        return jdbcTemplate.query(
                selectItems(
                        "i.set_id=? AND i.type=? AND i.exposed_at IS NOT NULL"
                                + validCandidateClause()
                                + " ORDER BY i.position,i.id LIMIT ?"),
                this::mapItem,
                setId,
                type,
                userId,
                userId,
                limit);
    }

    private List<ServingItem> findUnexposedForUpdate(
            long userId, long setId, String type, int limit) {
        return jdbcTemplate.query(
                selectItems(
                        "i.set_id=? AND i.type=? AND i.exposed_at IS NULL"
                                + validCandidateClause()
                                + " ORDER BY i.position,i.id LIMIT ? FOR UPDATE OF i"),
                this::mapItem,
                setId,
                type,
                userId,
                userId,
                limit);
    }

    private String selectItems(String where) {
        return "SELECT i.id,i.movie_id,i.type,COALESCE(i.flavor_code,pf.code) AS flavor_code,"
                + "i.reason::text AS reason,"
                + "m.title,m.poster_url,m.release_date,m.average_rating"
                + " FROM recommendation_items i JOIN movies m ON m.id=i.movie_id"
                + " LEFT JOIN popcorn_flavors pf ON pf.id=m.representative_flavor_id WHERE "
                + where;
    }

    private String validCandidateClause() {
        return " AND NOT EXISTS (SELECT 1 FROM user_movie_dismissals d"
                + " WHERE d.user_id=? AND d.movie_id=i.movie_id)"
                + " AND NOT EXISTS (SELECT 1 FROM ratings r"
                + " WHERE r.user_id=? AND r.movie_id=i.movie_id)";
    }

    private RecommendationSet mapSet(ResultSet resultSet) throws SQLException {
        return new RecommendationSet(
                resultSet.getLong("id"), resultSet.getObject("generated_at", OffsetDateTime.class));
    }

    private ServingItem mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
        Date releaseDate = resultSet.getDate("release_date");
        return new ServingItem(
                resultSet.getLong("id"),
                resultSet.getLong("movie_id"),
                resultSet.getString("type"),
                resultSet.getString("flavor_code"),
                resultSet.getString("reason"),
                resultSet.getString("title"),
                resultSet.getString("poster_url"),
                releaseDate == null ? null : releaseDate.toLocalDate().getYear(),
                resultSet.getBigDecimal("average_rating"));
    }

    public boolean expose(List<ServingItem> items, OffsetDateTime exposedAt) {
        int[][] results =
                jdbcTemplate.batchUpdate(
                        "UPDATE recommendation_items SET exposed_at=?"
                                + " WHERE id=? AND exposed_at IS NULL",
                        items,
                        items.size(),
                        (statement, item) -> {
                            statement.setObject(1, exposedAt);
                            statement.setLong(2, item.id());
                        });
        return java.util.Arrays.stream(results).flatMapToInt(java.util.Arrays::stream).count()
                        == items.size()
                && java.util.Arrays.stream(results)
                        .flatMapToInt(java.util.Arrays::stream)
                        .allMatch(updated -> updated == 1);
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
                                                key -> new ArrayList<>())
                                        .add(resultSet.getString("value")),
                movieIds.toArray());
        return grouped;
    }

    public record RecommendationSet(long id, OffsetDateTime generatedAt) {}

    public record ServingItem(
            long id,
            long movieId,
            String type,
            String flavorCode,
            String reason,
            String title,
            String posterUrl,
            Integer releaseYear,
            BigDecimal averageRating) {}
}
