package com.feelm.backend.domain.recommendation.repository;

import java.sql.Date;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 현재 사용자에게 제공 가능한 추천 후보의 OTT·영화 메타데이터를 한 번에 읽는다. */
@Repository
@RequiredArgsConstructor
public class OttRecommendationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public List<OttMovie> findAvailableMovies(long userId, List<Long> movieIds, Instant now) {
        if (movieIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parameters =
                new MapSqlParameterSource("userId", userId)
                        .addValue("movieIds", movieIds)
                        .addValue("now", now.atOffset(ZoneOffset.UTC));
        return jdbc.query(
                """
                SELECT o.id AS ott_id, o.code, o.name, o.logo_url,
                       m.id AS movie_id, m.title, m.poster_url, m.release_date,
                       EXISTS (SELECT 1 FROM user_otts uo
                               WHERE uo.user_id = :userId AND uo.ott_id = o.id) AS subscribed
                FROM movies m
                JOIN movie_ott_providers mop ON mop.movie_id = m.id
                JOIN otts o ON o.id = mop.ott_id
                WHERE m.id IN (:movieIds)
                  AND NOT EXISTS (SELECT 1 FROM ratings r
                                  WHERE r.user_id = :userId AND r.movie_id = m.id)
                  AND NOT EXISTS (SELECT 1 FROM user_movie_dismissals d
                                  WHERE d.user_id = :userId AND d.movie_id = m.id)
                  AND NOT EXISTS (SELECT 1 FROM watch_records w
                                  WHERE w.user_id = :userId AND w.movie_id = m.id
                                    AND (w.status IN ('WATCHED_UNRATED', 'RATED')
                                         OR (w.status = 'LINK_CLICKED'
                                             AND (w.expires_at IS NULL OR w.expires_at > :now))))
                ORDER BY o.id, m.id
                """,
                parameters,
                (resultSet, rowNumber) -> {
                    Date releaseDate = resultSet.getDate("release_date");
                    return new OttMovie(
                            resultSet.getLong("ott_id"),
                            resultSet.getString("code"),
                            resultSet.getString("name"),
                            resultSet.getString("logo_url"),
                            resultSet.getBoolean("subscribed"),
                            resultSet.getLong("movie_id"),
                            resultSet.getString("title"),
                            resultSet.getString("poster_url"),
                            releaseDate == null ? null : releaseDate.toLocalDate().getYear());
                });
    }

    public record OttMovie(
            long ottId,
            String code,
            String name,
            String logoUrl,
            boolean subscribed,
            long movieId,
            String title,
            String posterUrl,
            Integer releaseYear) {}
}
