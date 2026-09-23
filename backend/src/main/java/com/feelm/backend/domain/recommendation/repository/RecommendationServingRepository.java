package com.feelm.backend.domain.recommendation.repository;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@RequiredArgsConstructor
public class RecommendationServingRepository {

    private final JdbcTemplate jdbcTemplate;

    public boolean lockActiveUserIfExists(long userId) {
        return !jdbcTemplate
                .queryForList(
                        "SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR NO KEY UPDATE",
                        Long.class,
                        userId)
                .isEmpty();
    }

    /**
     * 서빙 대상 세트를 고른다.
     *
     * <p>최신 generation 안에서 아직 소진되지 않은 가장 낮은 {@code batch_no}를 쓴다. 프리페치로 다음 회차를 미리 만들어도 현재 회차에 남은 유효
     * 카드를 건너뛰지 않기 위해서다(S15P21E106-708). generation을 먼저 고르므로 새 generation이 도착하면 이전 generation에 유효
     * 카드가 남아 있어도 즉시 전환하는 기존 규칙(API-045)은 그대로다.
     *
     * <p>단, 이미 노출이 시작된 회차보다 뒤로는 가지 않는다. 지나간 회차의 카드가 평가 철회 등으로 되살아나도 그 회차로 되돌아가지 않는다. 되돌아가면 같은
     * generation에서 이미 보여준 영화를 다시 보여주게 되어, 다음 회차가 이전 회차의 영화를 제외한다는 규칙(S15P21E106-219)과 어긋난다.
     *
     * <p>최신 generation의 모든 회차가 소진됐으면 가장 최근 세트를 돌려준다. 소진 흐름(S15P21E106-219)이 그 세트를 기준으로 다음 회차를 만든다.
     *
     * <p>{@code generation_id}가 NULL인 레거시 세트(Candidate 메타데이터 도입 전 데이터)도 같은 폴백을 탄다. 회차 개념이 없어 전부
     * {@code batch_no=0}이고, 이들을 한 generation으로 묶으면 가장 오래된 세트를 고르게 되어 교체 전 동작과 정반대가 된다.
     */
    public Optional<RecommendationSet> findCurrentSet(long userId) {
        Optional<RecommendationSet> servable = findLowestServableSet(userId);
        return servable.isPresent() ? servable : findLatestSet(userId);
    }

    private Optional<RecommendationSet> findLowestServableSet(long userId) {
        return jdbcTemplate
                .query(
                        // latest: 최신 generation. generation_id가 NULL인 레거시 세트는 회차 개념이 없고
                        // NULL IS NOT DISTINCT FROM NULL이 참이라 서로 묶여 버리므로, 이 경로를 비워
                        // findLatestSet(=교체 전 동작)으로 넘긴다.
                        // progress: 그 generation에서 노출이 시작된 가장 높은 회차. 여기보다 뒤로 가지 않는다.
                        """
                        WITH latest AS (
                            SELECT generation_id FROM recommendation_sets
                            WHERE user_id=? AND party_id IS NULL
                            ORDER BY id DESC LIMIT 1
                        ), progress AS (
                            SELECT coalesce(max(s.batch_no),0) AS batch_no
                            FROM recommendation_sets s, latest
                            WHERE s.user_id=? AND s.party_id IS NULL
                              AND s.generation_id = latest.generation_id
                              AND EXISTS (
                                SELECT 1 FROM recommendation_items i
                                WHERE i.set_id=s.id AND i.exposed_at IS NOT NULL
                                  AND i.type IN ('TASTE','DISCOVERY')
                              )
                        )
                        SELECT s.id,s.generated_at,s.generation_id,s.batch_no
                        FROM recommendation_sets s, latest, progress
                        WHERE s.user_id=? AND s.party_id IS NULL
                          AND s.generation_id = latest.generation_id
                          AND s.batch_no >= progress.batch_no
                          AND EXISTS (
                            SELECT 1 FROM recommendation_items i
                            JOIN movies m ON m.id=i.movie_id
                            WHERE i.set_id=s.id AND i.type IN ('TASTE','DISCOVERY')
                        """
                                + validCandidateClause()
                                + """
                          )
                        ORDER BY s.batch_no,s.id LIMIT 1
                        """,
                        (resultSet, rowNumber) -> mapSet(resultSet),
                        userId,
                        userId,
                        userId,
                        userId,
                        userId)
                .stream()
                .findFirst();
    }

    private Optional<RecommendationSet> findLatestSet(long userId) {
        return jdbcTemplate
                .query(
                        selectSet("s.user_id=? AND s.party_id IS NULL ORDER BY s.id DESC LIMIT 1"),
                        (resultSet, rowNumber) -> mapSet(resultSet),
                        userId)
                .stream()
                .findFirst();
    }

    /**
     * 현재 세트에 아직 노출하지 않은 유효 후보 수다. 프리페치 판단에 쓴다(S15P21E106-708).
     *
     * <p>{@link #hasValidItems}와 같은 조인·술어를 쓰되 미노출 항목만 센다. 이미 노출된 카드는 다음 회차가 대체할 대상이 아니라 화면에 남는 카드라서
     * 남은 버퍼 깊이로 볼 수 없다.
     */
    public int countUnexposedValidItems(long userId, long setId) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM recommendation_items i"
                                + " JOIN movies m ON m.id=i.movie_id"
                                + " WHERE i.set_id=? AND i.type IN ('TASTE','DISCOVERY')"
                                + " AND i.exposed_at IS NULL"
                                + validCandidateClause(),
                        Integer.class,
                        setId,
                        userId,
                        userId);
        return count == null ? 0 : count;
    }

    private String selectSet(String where) {
        return "SELECT s.id,s.generated_at,s.generation_id,s.batch_no"
                + " FROM recommendation_sets s WHERE "
                + where;
    }

    public List<ServingItem> findInitialVisibleItems(
            long userId, long setId, int tasteTarget, int discoveryTarget) {
        List<ServingItem> discovery = findExposed(userId, setId, "DISCOVERY", discoveryTarget);
        int tasteSize = tasteTarget + Math.max(0, discoveryTarget - discovery.size());
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

    public List<ServingItem> findAndLockInitialItems(
            long userId, long setId, int tasteTarget, int discoveryTarget) {
        List<ServingItem> discovery =
                findUnexposedForUpdate(userId, setId, "DISCOVERY", discoveryTarget);
        int tasteSize = tasteTarget + Math.max(0, discoveryTarget - discovery.size());
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
                resultSet.getLong("id"),
                resultSet.getObject("generated_at", OffsetDateTime.class),
                resultSet.getString("generation_id"),
                resultSet.getInt("batch_no"));
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
     * 항목을 노출 상태로 기록하고 같은 트랜잭션에서 추천 이력을 갱신한다. 한 번에 노출하는 모든 항목을 함께 넘길 때만 쓴다 — 호출을 여러 번 나누면 {@link
     * #recordExposureHistory}가 매번 그 호출 안에서만 movie_id를 정렬해, 호출 간 잠금 순서는 온보딩과 여전히 어긋날 수
     * 있다(S15P21E106-804). 항목을 하나씩 순차로 노출해야 하는 호출자(예: 빈 슬롯 보충)는 {@link #markExposed}로 items만 갱신하고, 다
     * 모은 뒤 {@link #recordExposureHistory}를 한 번만 호출한다.
     *
     * <p>{@code user_movie_exposures} upsert를 여기 두는 이유는 이 메서드가 <b>개인 세트</b>의 {@code exposed_at}을 찍는
     * 유일한 지점이기 때문이다(파티는 {@code PartyRecommendationRepository.expose()}가 따로 찍고, {@code user_id}가 없어
     * 개인 이력 대상이 아니다). 두 쓰기가 같은 트랜잭션에서 커밋되므로 원본과 이력이 어긋나는 창이 없다. 호출자가 사용자 행 잠금을 쥐고 있어 같은 사용자의 동시 노출도
     * 직렬화된다.
     */
    public boolean expose(
            long userId, long setId, List<ServingItem> items, OffsetDateTime exposedAt) {
        boolean exposed = markExposed(items, exposedAt);
        if (exposed) {
            recordExposureHistory(userId, setId, items, exposedAt);
        }
        return exposed;
    }

    /**
     * 항목을 노출 상태로만 기록한다({@code recommendation_items.exposed_at}). movies를 참조하지 않아 온보딩의 movies 잠금과
     * 무관하다. 이력 기록({@link #recordExposureHistory})은 호출자가 별도로, 가능하면 한 트랜잭션의 모든 노출분을 모아 한 번만 한다.
     */
    public boolean markExposed(List<ServingItem> items, OffsetDateTime exposedAt) {
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

    /**
     * 같은 영화의 재노출은 행을 늘리지 않고 최신 노출로 갱신한다. 이력의 중복 제거가 여기서 끝난다.
     *
     * <p>{@code WHERE ... set_id < EXCLUDED.set_id} 가드는 더 오래된 세트의 노출이 최신 승자를 덮어쓰지 못하게 한다. 서빙 세트 ID는
     * 시간에 대해 비감소이므로 평상시에는 걸리지 않지만, 이 가드가 있어야 "이력의 승자는 언제나 최대 set_id"가 호출 순서와 무관하게 참이 된다. 운영에서 가드에
     * 걸리는 것은 시퀀스 되감기 같은 이상 신호라 경고를 남긴다.
     *
     * <p>S15P21E106-708 이후 서빙이 "항상 최신 세트"는 아니다. 프리페치가 만든 회차가 있으면 더 낮은 {@code batch_no}를 계속 노출한다.
     * 그래도 비감소는 유지된다 — 같은 generation 안에서는 {@link #findCurrentSet}의 진행 하한이 역행을 막고, generation 전환은 항상
     * 더 큰 ID로 간다.
     *
     * <p>테스트 픽스처 {@code RecommendationHistoryApiContractTest.recordExposure()}와 마이그레이션 {@code
     * V202609211000}의 백필이 이 문장을 복제한다. 가드나 컬럼을 바꾸면 셋을 함께 바꾼다.
     */
    /**
     * 트랜잭션 안에서 노출한 모든 항목을 한 번에 넘긴다. 여러 호출로 나누면 각 호출 안에서만 movie_id가 정렬되어 호출 간 잠금 순서가 어긋날 수
     * 있다(S15P21E106-804 리뷰 B-F6). {@link #markExposed}로 항목별 노출은 미리 끝내고 이 메서드는 트랜잭션당 한 번만 부른다.
     */
    public void recordExposureHistory(
            long userId, long setId, List<ServingItem> items, OffsetDateTime exposedAt) {
        if (items.isEmpty()) {
            return;
        }
        // FK 검사가 movies 행에 KEY SHARE를 행 순서대로 건다. 온보딩(API-024)이 movies를 id 오름차순 FOR UPDATE로
        // 여러 개 잠그므로 같은 방향으로 맞춰 다른 사용자 간 교착을 막는다(S15P21E106-804). 응답 순서와 무관하다.
        List<ServingItem> byMovieId =
                items.stream().sorted(Comparator.comparingLong(ServingItem::movieId)).toList();
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
                        byMovieId,
                        byMovieId.size(),
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

    public record RecommendationSet(
            long id, OffsetDateTime generatedAt, String generationId, int batchNo) {}

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
