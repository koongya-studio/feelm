package com.feelm.backend.domain.party.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * API-083 파티 추천 이력을 읽는다(S15P21E106-815).
 *
 * <p>정본은 {@code party_movie_exposures}다. 개인 이력의 {@code user_movie_exposures}(S15P21E106-671)와 같은
 * 구조를 파티 단위로 둔 것으로, {@code (party_id, movie_id)}가 기본키라 같은 영화의 재노출이 행을 늘리지 않으므로 읽는 쪽은 "같은 영화는 최신 세트
 * 1건만" 규칙을 따로 계산하지 않는다. 쓰기는 {@link PartyRecommendationRepository#expose}가 {@code exposed_at}과 같은
 * 트랜잭션에서 한다.
 *
 * <p>그래서 두 질의 모두 파티 이력 전체가 아니라 요청한 페이지만큼만 읽는다. 파티원이 평가할수록 세트가 늘어도 비용이 그에 비례해 자라지 않는다.
 *
 * <p>{@code recommendation_items}는 {@code position}과 영화를 가져오려고만 조인한다. 이력 표는 그 값을 복사하지 않고 항목을 가리키기만
 * 한다. JdbcTemplate을 쓰는 이유는 같은 도메인의 {@link PartyRecommendationRepository}가 같은 표를 같은 방식으로 읽기 때문이다(컨벤션
 * 9장).
 */
@Repository
@RequiredArgsConstructor
public class PartyRecommendationHistoryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * 파티의 세트를 최신순으로 한 페이지 읽는다. 이력 표에 행이 남은 세트만 나오므로 응답에 빈 세트가 생기지 않는다.
     *
     * @param cursorSetId 이 값보다 작은 세트만. 첫 페이지는 0
     */
    public List<HistorySet> findSets(long partyId, long cursorSetId, int limit) {
        String cursorCondition = cursorSetId == 0 ? "" : " AND e.set_id < :cursorSetId";
        MapSqlParameterSource parameters =
                new MapSqlParameterSource("partyId", partyId).addValue("limit", limit);
        if (cursorSetId != 0) {
            parameters.addValue("cursorSetId", cursorSetId);
        }
        return jdbcTemplate.query(
                """
                SELECT s.id, s.generated_at
                FROM (
                    SELECT DISTINCT e.set_id
                    FROM party_movie_exposures e
                    WHERE e.party_id = :partyId
                """
                        + cursorCondition
                        + """
                    ORDER BY e.set_id DESC
                    LIMIT :limit
                ) p
                JOIN recommendation_sets s ON s.id = p.set_id
                ORDER BY s.id DESC
                """,
                parameters,
                (resultSet, rowNumber) ->
                        new HistorySet(
                                resultSet.getLong("id"),
                                utc(resultSet.getObject("generated_at", OffsetDateTime.class))));
    }

    /** 세트들의 이력 항목을 세트 최신순, 세트 안에서는 노출 시각·순위 순으로 읽는다. */
    public List<HistoryItem> findItems(long partyId, List<Long> setIds) {
        if (setIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT e.set_id, i.id, i.movie_id, e.exposed_at,
                       m.title, m.poster_url, m.release_date, m.average_rating
                FROM party_movie_exposures e
                JOIN recommendation_items i ON i.id = e.recommendation_item_id
                JOIN movies m ON m.id = i.movie_id
                WHERE e.party_id = :partyId AND e.set_id IN (:setIds)
                ORDER BY e.set_id DESC, e.exposed_at, i.position, i.id
                """,
                new MapSqlParameterSource("partyId", partyId).addValue("setIds", setIds),
                this::mapItem);
    }

    private HistoryItem mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
        Date releaseDate = resultSet.getDate("release_date");
        return new HistoryItem(
                resultSet.getLong("set_id"),
                resultSet.getLong("id"),
                resultSet.getLong("movie_id"),
                utc(resultSet.getObject("exposed_at", OffsetDateTime.class)),
                resultSet.getString("title"),
                resultSet.getString("poster_url"),
                releaseDate == null ? null : releaseDate.toLocalDate().getYear(),
                resultSet.getBigDecimal("average_rating"));
    }

    /** API-058 응답과 같은 표기(UTC)로 맞춘다. */
    private static OffsetDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    public record HistorySet(long id, OffsetDateTime generatedAt) {}

    public record HistoryItem(
            long setId,
            long id,
            long movieId,
            OffsetDateTime exposedAt,
            String title,
            String posterUrl,
            Integer releaseYear,
            BigDecimal averageRating) {}
}
