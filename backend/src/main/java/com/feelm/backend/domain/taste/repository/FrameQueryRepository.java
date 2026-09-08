package com.feelm.backend.domain.taste.repository;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class FrameQueryRepository {

    private static final String LIST_SELECT =
            """
            SELECT f.id, f.created_at, m.id, m.title, m.poster_url, r.score, pf.code
            FROM frames f
            JOIN ratings r ON r.id = f.rating_id
            JOIN movies m ON m.id = f.movie_id
            LEFT JOIN popcorn_flavors pf ON pf.id = m.representative_flavor_id
            WHERE f.user_id = :userId
            """;
    private static final String FLAVOR_CONDITION = " AND pf.code = :flavorCode";
    private static final String COUNT_SELECT =
            """
            SELECT COUNT(f.id)
            FROM frames f
            JOIN movies m ON m.id = f.movie_id
            LEFT JOIN popcorn_flavors pf ON pf.id = m.representative_flavor_id
            WHERE f.user_id = :userId
            """;

    private final EntityManager entityManager;

    public FrameQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<FrameListRow> findFrames(
            long userId, String flavorCode, FrameCursor cursor, int limit) {
        String cursorCondition =
                cursor == null
                        ? ""
                        : """
                         AND (f.created_at < :cursorCreatedAt
                              OR (f.created_at = :cursorCreatedAt AND f.id < :cursorFrameId))
                        """;
        var query =
                entityManager.createNativeQuery(
                        LIST_SELECT
                                + flavorCondition(flavorCode)
                                + cursorCondition
                                + " ORDER BY f.created_at DESC, f.id DESC");
        query.setParameter("userId", userId);
        if (flavorCode != null) {
            query.setParameter("flavorCode", flavorCode);
        }
        if (cursor != null) {
            query.setParameter("cursorCreatedAt", cursor.createdAt());
            query.setParameter("cursorFrameId", cursor.frameId());
        }
        query.setMaxResults(limit);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(this::toListRow).toList();
    }

    public long countFrames(long userId, String flavorCode) {
        var query = entityManager.createNativeQuery(COUNT_SELECT + flavorCondition(flavorCode));
        query.setParameter("userId", userId);
        if (flavorCode != null) {
            query.setParameter("flavorCode", flavorCode);
        }
        Object result = query.getSingleResult();
        return ((Number) result).longValue();
    }

    private String flavorCondition(String flavorCode) {
        return flavorCode == null ? "" : FLAVOR_CONDITION;
    }

    public Optional<FrameDetailRow> findDetail(long userId, long frameId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows =
                entityManager
                        .createNativeQuery(
                                """
                                SELECT f.id,
                                       m.id,
                                       m.title,
                                       m.poster_url,
                                       m.release_date,
                                       pf.code,
                                       r.id,
                                       r.score,
                                       r.comment,
                                       r.visibility,
                                       wr.id,
                                       o.code,
                                       o.name,
                                       wr.clicked_at,
                                       wr.watched_at,
                                       wr.rated_at
                                FROM frames f
                                JOIN ratings r ON r.id = f.rating_id
                                JOIN movies m ON m.id = f.movie_id
                                LEFT JOIN popcorn_flavors pf
                                    ON pf.id = m.representative_flavor_id
                                LEFT JOIN watch_records wr ON wr.id = r.watch_record_id
                                LEFT JOIN otts o ON o.id = wr.ott_id
                                WHERE f.id = :frameId AND f.user_id = :userId
                                """)
                        .setParameter("frameId", frameId)
                        .setParameter("userId", userId)
                        .getResultList();
        return rows.stream().findFirst().map(this::toDetailRow);
    }

    public List<String> findGenres(long movieId) {
        @SuppressWarnings("unchecked")
        List<String> genres =
                entityManager
                        .createNativeQuery(
                                """
                                SELECT g.name
                                FROM movie_genres mg
                                JOIN genres g ON g.id = mg.genre_id
                                WHERE mg.movie_id = :movieId
                                ORDER BY g.name
                                """,
                                String.class)
                        .setParameter("movieId", movieId)
                        .getResultList();
        return genres;
    }

    public List<String> findDirectors(long movieId) {
        @SuppressWarnings("unchecked")
        List<String> directors =
                entityManager
                        .createNativeQuery(
                                """
                                SELECT p.name
                                FROM movie_people mp
                                JOIN people p ON p.id = mp.person_id
                                WHERE mp.movie_id = :movieId AND mp.role = 'DIRECTOR'
                                ORDER BY mp.display_order NULLS LAST, p.name
                                """,
                                String.class)
                        .setParameter("movieId", movieId)
                        .getResultList();
        return directors;
    }

    private FrameListRow toListRow(Object[] row) {
        return new FrameListRow(
                number(row[0]),
                toInstant(row[1]),
                number(row[2]),
                (String) row[3],
                (String) row[4],
                decimal(row[5]),
                (String) row[6]);
    }

    private FrameDetailRow toDetailRow(Object[] row) {
        return new FrameDetailRow(
                number(row[0]),
                number(row[1]),
                (String) row[2],
                (String) row[3],
                toLocalDate(row[4]),
                (String) row[5],
                number(row[6]),
                decimal(row[7]),
                (String) row[8],
                (String) row[9],
                row[10] == null ? null : number(row[10]),
                (String) row[11],
                (String) row[12],
                toInstant(row[13]),
                toInstant(row[14]),
                toInstant(row[15]));
    }

    private long number(Object value) {
        return ((Number) value).longValue();
    }

    private BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        throw new IllegalStateException("지원하지 않는 날짜 타입입니다: " + value.getClass());
    }

    private Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("지원하지 않는 시각 타입입니다: " + value.getClass());
    }

    public record FrameCursor(Instant createdAt, long frameId) {}

    public record FrameListRow(
            long frameId,
            Instant createdAt,
            long movieId,
            String title,
            String posterUrl,
            BigDecimal score,
            String flavorCode) {}

    public record FrameDetailRow(
            long frameId,
            long movieId,
            String title,
            String posterUrl,
            LocalDate releaseDate,
            String flavorCode,
            long ratingId,
            BigDecimal score,
            String comment,
            String visibility,
            Long watchRecordId,
            String ottCode,
            String ottName,
            Instant clickedAt,
            Instant watchedAt,
            Instant ratedAt) {}
}
