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
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 파티 추천 후보 필터와 세트·아이템 이력 저장을 담당한다. */
@Slf4j
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
                        "SELECT id,generated_at,recommendation_mode FROM recommendation_sets"
                                + " WHERE party_id=? AND user_id IS NULL"
                                + " AND input_version=? AND model_version=?",
                        (resultSet, rowNumber) ->
                                new RecommendationSet(
                                        resultSet.getLong("id"),
                                        resultSet.getObject("generated_at", OffsetDateTime.class),
                                        resultSet.getString("recommendation_mode")),
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

    public Optional<OffsetDateTime> findLatestPartyExposedAt(long partyId) {
        return Optional.ofNullable(
                jdbcTemplate.queryForObject(
                        "SELECT max(i.exposed_at) FROM recommendation_items i JOIN recommendation_sets s ON s.id=i.set_id WHERE s.party_id=?",
                        OffsetDateTime.class,
                        partyId));
    }

    public void saveMode(long setId, String mode) {
        jdbcTemplate.update(
                "UPDATE recommendation_sets SET recommendation_mode=? WHERE id=?", mode, setId);
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

    /**
     * 항목을 노출 상태로 기록하고 같은 트랜잭션에서 파티 추천 이력({@code party_movie_exposures})을 갱신한다(S15P21E106-815).
     *
     * <p>이 메서드가 <b>파티 세트</b>의 {@code exposed_at}을 찍는 유일한 지점이므로 이력 upsert도 여기 둔다. 개인 세트의 {@code
     * RecommendationServingRepository.expose()}와 같은 배치다. 두 쓰기가 같은 트랜잭션(파티 행 잠금 안)에서 커밋되므로 원본과 이력이
     * 어긋나는 창이 없고, 같은 파티의 동시 다시 받기도 직렬화된다.
     */
    public void expose(long partyId, List<StoredItem> items, OffsetDateTime exposedAt) {
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
        recordExposureHistory(partyId, items);
    }

    /**
     * 같은 영화의 재노출은 행을 늘리지 않고 최신 노출로 갱신한다. 이력(API-083)의 "같은 영화는 최신 세트 1건" 규칙이 여기서 끝난다.
     *
     * <p>{@code set_id}와 {@code exposed_at}은 인자가 아니라 <b>항목 행에서</b> 읽는다. 바로 앞의 UPDATE가 {@code
     * COALESCE(exposed_at, ?)}로 이미 노출된 항목의 시각을 보존하므로, 인자를 넣으면 원본과 이력이 어긋나는 경로가 남는다(리뷰 A-1·B-5). 행에서
     * 읽으면 원본이 무엇이든 이력은 그것을 가리킨다. {@code exposed_at IS NOT NULL} 조건은 UPDATE가 실패한 행을 이력에 넣지 않기 위한
     * 방어다.
     *
     * <p>{@code WHERE ... set_id < EXCLUDED.set_id} 가드는 더 오래된 세트의 노출이 최신 승자를 덮어쓰지 못하게 한다. 이 가드는
     * <b>정상 운영에서도 걸린다</b>(리뷰 A-2·B-1). 파티 입력은 파티원과 각자의 후보 세대의 해시라, 파티원이 나갔다가 돌아오면 입력이 이전 값으로 되돌아가
     * {@code findExistingSet}이 예전 세트를 다시 서빙한다. 그 세트가 새로 노출한 영화 중 더 새 세트에 이미 기록된 영화는 가드에 걸려 이력 위치가
     * 바뀌지 않는다 — 이것이 계약(세트 ID 내림차순, 최신 세트 1건)이 요구하는 결과이므로 오류가 아니라 정보 로그로만 남긴다.
     *
     * <p>FK 검사가 movies 행에 KEY SHARE를 행 순서대로 걸므로 {@link #insertItems}와 같이 movie_id 오름차순으로 넣어
     * 온보딩(API-024)의 잠금 방향과 맞춘다(S15P21E106-804). 테스트 픽스처 {@code
     * PartyRecommendationHistoryApiContractTest.item()}과 마이그레이션 {@code V202609231400}의 백필이 같은 승자
     * 규칙을 쓴다. 가드나 컬럼을 바꾸면 셋을 함께 바꾼다.
     */
    private void recordExposureHistory(long partyId, List<StoredItem> items) {
        List<StoredItem> byMovieId =
                items.stream().sorted(Comparator.comparingLong(StoredItem::movieId)).toList();
        int[][] results =
                jdbcTemplate.batchUpdate(
                        """
                        INSERT INTO party_movie_exposures
                            (party_id, movie_id, recommendation_item_id, set_id, exposed_at)
                        SELECT ?, ?, ?, i.set_id, i.exposed_at
                        FROM recommendation_items i
                        WHERE i.id = ? AND i.exposed_at IS NOT NULL
                        ON CONFLICT (party_id, movie_id) DO UPDATE SET
                            recommendation_item_id = EXCLUDED.recommendation_item_id,
                            set_id = EXCLUDED.set_id,
                            exposed_at = EXCLUDED.exposed_at
                        WHERE party_movie_exposures.set_id < EXCLUDED.set_id
                        """,
                        byMovieId,
                        byMovieId.size(),
                        (statement, item) -> {
                            statement.setLong(1, partyId);
                            statement.setLong(2, item.movieId());
                            statement.setLong(3, item.id());
                            statement.setLong(4, item.id());
                        });
        long recorded =
                java.util.Arrays.stream(results).flatMapToInt(java.util.Arrays::stream).sum();
        if (recorded < items.size()) {
            log.info(
                    "Party exposure history kept newer sets for {} of {} rows in party {}:"
                            + " an older set was re-served after a newer one",
                    items.size() - recorded,
                    items.size(),
                    partyId);
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

    public record RecommendationSet(
            long id, OffsetDateTime generatedAt, String recommendationMode) {
        public RecommendationSet(long id, OffsetDateTime generatedAt) {
            this(id, generatedAt, "PERSONALIZED");
        }
    }

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
