package com.feelm.backend.domain.recommendation.repository;

import com.feelm.backend.domain.recommendation.model.RecommendationCandidate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 추천 버퍼 생성에 필요한 사용자 잠금, 후보 필터 조회, 세트·아이템 저장 SQL을 담당한다. */
@Repository
@RequiredArgsConstructor
public class RecommendationBufferRepository {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public boolean lockActiveUserIfExists(long userId) {
        return !jdbcTemplate
                .queryForList(
                        "SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR NO KEY UPDATE",
                        Long.class,
                        userId)
                .isEmpty();
    }

    /** 같은 generation에 회차가 여럿이면 최신 회차를 결정적으로 반환한다. */
    public OptionalLong findExistingSet(long userId, String generationId) {
        return jdbcTemplate
                .queryForList(
                        "SELECT id FROM recommendation_sets"
                                + " WHERE party_id IS NULL AND user_id=? AND generation_id=?"
                                + " ORDER BY batch_no DESC,id DESC LIMIT 1",
                        Long.class,
                        userId,
                        generationId)
                .stream()
                .mapToLong(Long::longValue)
                .findFirst();
    }

    /** 같은 generation에서 지금까지 만든 마지막 회차 번호다. */
    public OptionalInt findLatestBatchNo(long userId, String generationId) {
        return jdbcTemplate
                .queryForList(
                        "SELECT batch_no FROM recommendation_sets"
                                + " WHERE party_id IS NULL AND user_id=? AND generation_id=?"
                                + " ORDER BY batch_no DESC LIMIT 1",
                        Integer.class,
                        userId,
                        generationId)
                .stream()
                .mapToInt(Integer::intValue)
                .findFirst();
    }

    /** 현재 서빙 대상인 가장 최근 개인 추천 세트 ID다. */
    public OptionalLong findLatestSetId(long userId) {
        return jdbcTemplate
                .queryForList(
                        "SELECT id FROM recommendation_sets"
                                + " WHERE party_id IS NULL AND user_id=? ORDER BY id DESC LIMIT 1",
                        Long.class,
                        userId)
                .stream()
                .mapToLong(Long::longValue)
                .findFirst();
    }

    /** 같은 generation의 이전 회차가 이미 저장한 영화다. 다음 회차에서 제외한다. */
    public Set<Long> findStoredMovieIdsInGeneration(long userId, String generationId) {
        return Set.copyOf(
                jdbcTemplate.queryForList(
                        "SELECT i.movie_id FROM recommendation_items i"
                                + " JOIN recommendation_sets s ON s.id=i.set_id"
                                + " WHERE s.party_id IS NULL AND s.user_id=? AND s.generation_id=?",
                        Long.class,
                        userId,
                        generationId));
    }

    /** 서비스에 존재하며 사용자가 평가하거나 관심없음 처리하지 않은 후보 영화 ID를 한 번에 조회한다. */
    public Set<Long> findEligibleMovieIds(long userId, List<RecommendationCandidate> candidates) {
        if (candidates.isEmpty()) {
            return Set.of();
        }
        List<Long> movieIds =
                candidates.stream().map(RecommendationCandidate::movieId).distinct().toList();
        MapSqlParameterSource parameters =
                new MapSqlParameterSource("userId", userId).addValue("movieIds", movieIds);
        return Set.copyOf(
                namedParameterJdbcTemplate.queryForList(
                        """
                        SELECT m.id
                        FROM movies m
                        WHERE m.id IN (:movieIds)
                          AND NOT EXISTS (
                            SELECT 1 FROM user_movie_dismissals d
                            WHERE d.user_id = :userId AND d.movie_id = m.id
                          )
                          AND NOT EXISTS (
                            SELECT 1 FROM ratings r
                            WHERE r.user_id = :userId AND r.movie_id = m.id
                          )
                        """,
                        parameters,
                        Long.class));
    }

    public long insertSet(
            long userId,
            String inputVersion,
            String modelVersion,
            long candidateVersion,
            String generationId,
            int batchNo,
            OffsetDateTime generatedAt) {
        Long id =
                jdbcTemplate.queryForObject(
                        "INSERT INTO recommendation_sets"
                                + " (user_id,input_version,model_version,"
                                + " candidate_version,generation_id,batch_no,generated_at)"
                                + " VALUES (?,?,?,?,?,?,?) RETURNING id",
                        Long.class,
                        userId,
                        inputVersion,
                        modelVersion,
                        candidateVersion,
                        generationId,
                        batchNo,
                        generatedAt);
        if (id == null) {
            throw new IllegalStateException("Recommendation set ID was not generated");
        }
        return id;
    }

    public void insertItems(long setId, List<RecommendationBufferItem> items) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO recommendation_items"
                        + " (set_id,movie_id,type,flavor_code,position,reason)"
                        + " VALUES (?,?,?,?,?,CAST(? AS jsonb))",
                items,
                items.size(),
                (statement, item) -> {
                    statement.setLong(1, setId);
                    statement.setLong(2, item.movieId());
                    statement.setString(3, item.type());
                    statement.setString(4, item.flavorCode());
                    statement.setInt(5, item.position());
                    statement.setString(6, item.reason().toString());
                });
    }

    public record RecommendationBufferItem(
            long movieId,
            String type,
            String flavorCode,
            tools.jackson.databind.JsonNode reason,
            int position) {}
}
