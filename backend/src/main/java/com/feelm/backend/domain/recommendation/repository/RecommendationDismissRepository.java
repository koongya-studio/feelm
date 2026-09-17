package com.feelm.backend.domain.recommendation.repository;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** API-047에 필요한 검증과 관심없음 저장 쿼리를 한곳에 둔다. */
@Repository
@RequiredArgsConstructor
public class RecommendationDismissRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 활성 사용자의 관심없음 요청을 직렬화해 사전 중복 검사와 삽입 사이의 경합을 닫는다. */
    public boolean lockActiveUserIfExists(long userId) {
        return !jdbcTemplate
                .queryForList(
                        "SELECT id FROM users WHERE id = ? AND status = 'ACTIVE'"
                                + " FOR NO KEY UPDATE",
                        Long.class,
                        userId)
                .isEmpty();
    }

    /** 영화 삭제와 관심없음 삽입이 교차하지 않도록 존재하는 영화 행에 key-share 잠금을 건다. */
    public boolean lockMovieIfExists(long movieId) {
        return !jdbcTemplate
                .queryForList(
                        "SELECT id FROM movies WHERE id = ? FOR KEY SHARE", Long.class, movieId)
                .isEmpty();
    }

    public boolean dismissalExists(long userId, long movieId) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM user_movie_dismissals"
                                + " WHERE user_id = ? AND movie_id = ?",
                        Integer.class,
                        userId,
                        movieId);
        return count != null && count > 0;
    }

    public Optional<SourceItem> findOwnedExposedSource(
            long userId, long movieId, long recommendationItemId) {
        return jdbcTemplate
                .query(
                        "SELECT ri.id, ri.set_id, ri.movie_id, ri.type"
                                + " FROM recommendation_items ri"
                                + " JOIN recommendation_sets rs ON rs.id = ri.set_id"
                                + " WHERE ri.id = ? AND ri.movie_id = ? AND ri.exposed_at IS NOT NULL"
                                + " AND rs.user_id = ? AND rs.party_id IS NULL",
                        (resultSet, rowNumber) ->
                                new SourceItem(
                                        resultSet.getLong("id"),
                                        resultSet.getLong("set_id"),
                                        resultSet.getLong("movie_id"),
                                        resultSet.getString("type")),
                        recommendationItemId,
                        movieId,
                        userId)
                .stream()
                .findFirst();
    }

    public void saveDismissal(long userId, long movieId, Long recommendationItemId) {
        jdbcTemplate.update(
                "INSERT INTO user_movie_dismissals(user_id,movie_id,recommendation_item_id)"
                        + " VALUES (?,?,?)",
                userId,
                movieId,
                recommendationItemId);
    }

    public List<Long> findDismissedMovieIds(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT movie_id FROM user_movie_dismissals WHERE user_id = ? ORDER BY movie_id",
                Long.class,
                userId);
    }

    public record SourceItem(long id, long setId, long movieId, String type) {}
}
