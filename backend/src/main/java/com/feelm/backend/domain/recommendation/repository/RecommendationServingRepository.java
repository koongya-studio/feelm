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
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Slf4j
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

    /**
     * 항목을 노출 상태로 기록하고 같은 트랜잭션에서 추천 이력을 갱신한다.
     *
     * <p>{@code user_movie_exposures} upsert를 여기 두는 이유는 이 메서드가 <b>개인 세트</b>의 {@code exposed_at}을 찍는
     * 유일한 지점이기 때문이다(파티는 {@code PartyRecommendationRepository.expose()}가 따로 찍고, {@code user_id}가 없어
     * 개인 이력 대상이 아니다). 두 쓰기가 같은 트랜잭션에서 커밋되므로 원본과 이력이 어긋나는 창이 없다. 호출자가 사용자 행 잠금을 쥐고 있어 같은 사용자의 동시 노출도
     * 직렬화된다.
     */
    public boolean expose(
            long userId, long setId, List<ServingItem> items, OffsetDateTime exposedAt) {
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
        boolean exposed =
                java.util.Arrays.stream(results).flatMapToInt(java.util.Arrays::stream).count()
                                == items.size()
                        && java.util.Arrays.stream(results)
                                .flatMapToInt(java.util.Arrays::stream)
                                .allMatch(updated -> updated == 1);
        if (exposed) {
            recordExposureHistory(userId, setId, items, exposedAt);
        }
        return exposed;
    }

    /**
     * 같은 영화의 재노출은 행을 늘리지 않고 최신 노출로 갱신한다. 이력의 중복 제거가 여기서 끝난다.
     *
     * <p>{@code WHERE ... set_id < EXCLUDED.set_id} 가드는 더 오래된 세트의 노출이 최신 승자를 덮어쓰지 못하게 한다. 현재 서빙은 항상
     * 최신 세트만 노출하므로 평상시에는 걸리지 않지만, 이 가드가 있어야 "이력의 승자는 언제나 최대 set_id"가 호출 순서와 무관하게 참이 된다. 운영에서 가드에
     * 걸리는 것은 시퀀스 되감기 같은 이상 신호라 경고를 남긴다.
     *
     * <p>테스트 픽스처 {@code RecommendationHistoryApiContractTest.recordExposure()}와 마이그레이션 {@code
     * V202609211000}의 백필이 이 문장을 복제한다. 가드나 컬럼을 바꾸면 셋을 함께 바꾼다.
     */
    private void recordExposureHistory(
            long userId, long setId, List<ServingItem> items, OffsetDateTime exposedAt) {
        int[][] results =
                jdbcTemplate.batchUpdate(
                        """
                INSERT INTO user_movie_exposures
                    (user_id, movie_id, recommendation_item_id, set_id, exposed_at)
                VALUES (?,?,?,?,?)
                ON CONFLICT (user_id, movie_id) DO UPDATE SET
                    recommendation_item_id = EXCLUDED.recommendation_item_id,
                    set_id = EXCLUDED.set_id,
                    exposed_at = EXCLUDED.exposed_at
                WHERE user_movie_exposures.set_id < EXCLUDED.set_id
                """,
                        items,
                        items.size(),
                        (statement, item) -> {
                            statement.setLong(1, userId);
                            statement.setLong(2, item.movieId());
                            statement.setLong(3, item.id());
                            statement.setLong(4, setId);
                            statement.setObject(5, exposedAt);
                        });
        long recorded =
                java.util.Arrays.stream(results).flatMapToInt(java.util.Arrays::stream).sum();
        if (recorded < items.size()) {
            log.warn(
                    "Exposure history guard rejected {} of {} rows for user {} set {}:"
                            + " an older set was exposed after a newer one",
                    items.size() - recorded,
                    items.size(),
                    userId,
                    setId);
        }
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
