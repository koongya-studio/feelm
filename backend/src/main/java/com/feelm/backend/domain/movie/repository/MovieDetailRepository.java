package com.feelm.backend.domain.movie.repository;

import com.feelm.backend.domain.movie.dto.response.MovieDetailResponse.MovieOttProviderResponse;
import com.feelm.backend.domain.movie.dto.response.MovieDetailResponse.MyRatingResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;

/**
 * 외부 적재 테이블의 읽기 전용 Projection. JSONB와 역할별 순위는 PostgreSQL에서 조회하고, 다대다 컬렉션을 분리해 행 증폭과 N+1을 피한다. 실제
 * PostgreSQL 계약 테스트로 검증한다.
 */
@Repository
@RequiredArgsConstructor
public class MovieDetailRepository {

    // API-030에서 승인한 제공처만 노출한다. DB ID나 검색 필터의 표시 순서에 의존하지 않는다.
    private static final List<String> SUPPORTED_OTT_CODES =
            List.of("NETFLIX", "DISNEY_PLUS", "WATCHA", "WAVVE", "TVING");

    private final EntityManager entityManager;

    public Optional<MovieMetadata> findMovie(long movieId) {
        return query(
                        """
                        SELECT m.id, m.title, m.original_title, m.synopsis,
                               cast(m.release_date AS text) AS release_date, m.runtime_minutes,
                               m.poster_url, m.backdrop_url, f.code AS flavor_code,
                               m.average_rating, m.rating_count
                        FROM movies m
                        LEFT JOIN popcorn_flavors f ON f.id = m.representative_flavor_id
                        WHERE m.id = :movieId
                        """)
                .setParameter("movieId", movieId)
                .getResultList()
                .stream()
                .findFirst()
                .map(
                        row ->
                                new MovieMetadata(
                                        row.get("id", Long.class),
                                        row.get("title", String.class),
                                        row.get("original_title", String.class),
                                        row.get("synopsis", String.class),
                                        row.get("release_date") == null
                                                ? null
                                                : LocalDate.parse(
                                                        row.get("release_date", String.class)),
                                        row.get("runtime_minutes", Integer.class),
                                        row.get("poster_url", String.class),
                                        row.get("backdrop_url", String.class),
                                        row.get("flavor_code", String.class),
                                        row.get("average_rating", BigDecimal.class),
                                        row.get("rating_count", Integer.class)));
    }

    public List<MovieAttribute> findAttributes(long movieId) {
        return query(
                        """
                        SELECT 'GENRE' AS kind, g.code, g.name, g.id AS sort_id
                        FROM movie_genres mg JOIN genres g ON g.id = mg.genre_id
                        WHERE mg.movie_id = :movieId
                        UNION ALL
                        SELECT 'COUNTRY' AS kind, country_code AS code, NULL AS name, 0 AS sort_id
                        FROM movie_countries WHERE movie_id = :movieId
                        ORDER BY kind, code, sort_id
                        """)
                .setParameter("movieId", movieId)
                .getResultList()
                .stream()
                .map(
                        row ->
                                new MovieAttribute(
                                        row.get("kind", String.class),
                                        row.get("code", String.class),
                                        row.get("name", String.class)))
                .toList();
    }

    public List<MovieCredit> findCredits(long movieId) {
        return query(
                        """
                        SELECT credits.person_id, p.name, p.profile_url,
                               credits.role, credits.character_name
                        FROM (
                            SELECT person_id, role, character_name,
                                   row_number() OVER (
                                       PARTITION BY role
                                       ORDER BY display_order NULLS LAST, person_id
                                   ) AS credit_order
                            FROM movie_people WHERE movie_id = :movieId
                        ) credits
                        JOIN people p ON p.id = credits.person_id
                        WHERE credits.role = 'DIRECTOR' OR credits.credit_order <= 10
                        ORDER BY credits.role, credits.credit_order
                        """)
                .setParameter("movieId", movieId)
                .getResultList()
                .stream()
                .map(
                        row ->
                                new MovieCredit(
                                        row.get("person_id", Long.class),
                                        row.get("name", String.class),
                                        row.get("role", String.class),
                                        row.get("character_name", String.class),
                                        row.get("profile_url", String.class)))
                .toList();
    }

    public List<MovieOttProviderResponse> findOttProviders(long movieId, Long userId) {
        NativeQuery<Tuple> providers;
        if (userId == null) {
            providers =
                    query(
                            """
                    SELECT o.id, o.code, o.name, p.watch_url, false AS subscribed
                    FROM movie_ott_providers p JOIN otts o ON o.id = p.ott_id
                    WHERE p.movie_id = :movieId
                      AND o.code IN (:supportedOttCodes)
                    ORDER BY o.id
                    """);
        } else {
            providers =
                    query(
                                    """
                    SELECT o.id, o.code, o.name, p.watch_url,
                           EXISTS (SELECT 1 FROM user_otts u
                                   WHERE u.user_id = :userId AND u.ott_id = o.id) AS subscribed
                    FROM movie_ott_providers p JOIN otts o ON o.id = p.ott_id
                    WHERE p.movie_id = :movieId
                      AND o.code IN (:supportedOttCodes)
                    ORDER BY subscribed DESC, o.id
                    """)
                            .setParameter("userId", userId);
        }
        return providers
                .setParameter("movieId", movieId)
                .setParameterList("supportedOttCodes", SUPPORTED_OTT_CODES)
                .getResultList()
                .stream()
                .map(
                        row ->
                                new MovieOttProviderResponse(
                                        row.get("id", Long.class),
                                        row.get("code", String.class),
                                        row.get("name", String.class),
                                        row.get("watch_url", String.class),
                                        row.get("subscribed", Boolean.class)))
                .toList();
    }

    public Personalization findPersonalization(long movieId, long userId) {
        Tuple row =
                query(
                                """
                                SELECT r.id AS rating_id, r.score, r.comment, r.visibility,
                                       r.is_spoiler, w.status
                        FROM (SELECT 1) seed
                        LEFT JOIN ratings r ON r.user_id = :userId AND r.movie_id = :movieId
                        LEFT JOIN watch_records w ON w.user_id = :userId AND w.movie_id = :movieId
                                                 AND w.status <> 'EXPIRED'
                        """)
                        .setParameter("movieId", movieId)
                        .setParameter("userId", userId)
                        .getSingleResult();
        MyRatingResponse rating =
                row.get("rating_id") == null
                        ? null
                        : new MyRatingResponse(
                                row.get("rating_id", Long.class),
                                row.get("score", BigDecimal.class),
                                row.get("comment", String.class),
                                row.get("visibility", String.class),
                                row.get("is_spoiler", Boolean.class));
        return new Personalization(rating, row.get("status", String.class));
    }

    public Optional<RecommendationSnapshot> findRecommendation(
            long movieId, long recommendationItemId, long userId) {
        return query(
                        """
                        SELECT i.id, i.type, cast(i.reason AS text) AS reason, s.user_id,
                               EXISTS (SELECT 1 FROM party_members pm
                                       WHERE pm.party_id = s.party_id AND pm.user_id = :userId
                               ) AS current_party_member
                        FROM recommendation_items i JOIN recommendation_sets s ON s.id = i.set_id
                        WHERE i.id = :itemId AND i.movie_id = :movieId
                        """)
                .setParameter("movieId", movieId)
                .setParameter("itemId", recommendationItemId)
                .setParameter("userId", userId)
                .getResultList()
                .stream()
                .findFirst()
                .map(
                        row ->
                                new RecommendationSnapshot(
                                        row.get("id", Long.class),
                                        row.get("type", String.class),
                                        row.get("reason", String.class),
                                        row.get("user_id", Long.class),
                                        row.get("current_party_member", Boolean.class)));
    }

    private NativeQuery<Tuple> query(String sql) {
        return entityManager.unwrap(Session.class).createNativeQuery(sql, Tuple.class);
    }

    public record MovieMetadata(
            long movieId,
            String title,
            String originalTitle,
            String synopsis,
            LocalDate releaseDate,
            Integer runtimeMinutes,
            String posterUrl,
            String backdropUrl,
            String flavorCode,
            BigDecimal averageRating,
            int ratingCount) {}

    public record MovieAttribute(String kind, String code, String name) {}

    public record MovieCredit(
            long personId, String name, String role, String character, String profileUrl) {}

    public record Personalization(MyRatingResponse rating, String watchStatus) {}

    public record RecommendationSnapshot(
            long recommendationItemId,
            String type,
            String reason,
            Long userId,
            boolean currentPartyMember) {}
}
