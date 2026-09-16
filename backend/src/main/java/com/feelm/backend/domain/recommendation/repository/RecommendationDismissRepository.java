package com.feelm.backend.domain.recommendation.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** API-047에 필요한 검증·관심없음 저장·버퍼 승격 쿼리를 한곳에 둔다. */
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

    /** 사용자의 최신 개인 추천 세트인지 확인한다. 생성 시각이 같으면 ID가 큰 세트를 최신으로 본다. */
    public boolean isCurrentPersonalSet(long userId, long setId) {
        return jdbcTemplate
                .queryForList(
                        "SELECT id FROM recommendation_sets"
                                + " WHERE user_id = ? AND party_id IS NULL"
                                + " ORDER BY generated_at DESC, id DESC LIMIT 1",
                        Long.class,
                        userId)
                .stream()
                .findFirst()
                .map(id -> id.longValue() == setId)
                .orElse(false);
    }

    /** 같은 세트·유형의 다음 미노출 후보를 잠가 동시 요청이 같은 후보를 고르지 못하게 한다. */
    public Optional<ReplacementItem> lockNextReplacement(long userId, long setId, String type) {
        return jdbcTemplate
                .query(
                        "SELECT id, movie_id, type, flavor_code, reason::text AS reason"
                                + " FROM recommendation_items"
                                + " WHERE set_id = ? AND type = ? AND exposed_at IS NULL"
                                + " AND NOT EXISTS (SELECT 1 FROM user_movie_dismissals d"
                                + " WHERE d.user_id = ?"
                                + " AND d.movie_id = recommendation_items.movie_id)"
                                + " ORDER BY position ASC LIMIT 1 FOR UPDATE SKIP LOCKED",
                        (resultSet, rowNumber) ->
                                new ReplacementItem(
                                        resultSet.getLong("id"),
                                        resultSet.getLong("movie_id"),
                                        resultSet.getString("type"),
                                        resultSet.getString("flavor_code"),
                                        resultSet.getString("reason")),
                        setId,
                        type,
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

    public boolean expose(long recommendationItemId, OffsetDateTime exposedAt) {
        return jdbcTemplate.update(
                        "UPDATE recommendation_items SET exposed_at = ?"
                                + " WHERE id = ? AND exposed_at IS NULL",
                        exposedAt,
                        recommendationItemId)
                == 1;
    }

    public MovieSummary findMovie(long movieId) {
        return jdbcTemplate.queryForObject(
                "SELECT m.id, m.title, m.poster_url, m.release_date, m.average_rating, pf.code"
                        + " AS flavor_code FROM movies m LEFT JOIN popcorn_flavors pf"
                        + " ON pf.id = m.representative_flavor_id WHERE m.id = ?",
                (resultSet, rowNumber) -> {
                    Date releaseDate = resultSet.getDate("release_date");
                    return new MovieSummary(
                            resultSet.getLong("id"),
                            resultSet.getString("title"),
                            resultSet.getString("poster_url"),
                            releaseDate == null ? null : releaseDate.toLocalDate().getYear(),
                            resultSet.getBigDecimal("average_rating"),
                            resultSet.getString("flavor_code"));
                },
                movieId);
    }

    public List<String> findGenreNames(long movieId) {
        return jdbcTemplate.queryForList(
                "SELECT g.name FROM movie_genres mg JOIN genres g ON g.id = mg.genre_id"
                        + " WHERE mg.movie_id = ? ORDER BY g.name, g.id",
                String.class,
                movieId);
    }

    public List<String> findOttCodes(long movieId) {
        return jdbcTemplate.queryForList(
                "SELECT o.code FROM movie_ott_providers mop JOIN otts o ON o.id = mop.ott_id"
                        + " WHERE mop.movie_id = ? ORDER BY o.code, o.id",
                String.class,
                movieId);
    }

    public List<Long> findDismissedMovieIds(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT movie_id FROM user_movie_dismissals WHERE user_id = ? ORDER BY movie_id",
                Long.class,
                userId);
    }

    public record SourceItem(long id, long setId, long movieId, String type) {}

    public record ReplacementItem(
            long id, long movieId, String type, String flavorCode, String reason) {}

    public record MovieSummary(
            long id,
            String title,
            String posterUrl,
            Integer releaseYear,
            BigDecimal averageRating,
            String flavorCode) {}
}
