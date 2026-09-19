package com.feelm.backend.domain.report.repository;

import com.feelm.backend.domain.report.model.ReportHalf;
import com.feelm.backend.domain.report.model.ReportPeriod;
import com.feelm.backend.domain.report.model.ReportSource;
import com.feelm.backend.domain.report.model.ReportSource.Role;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReportGenerationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ReportGenerationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LockAttempt tryLock(ReportPeriod period) {
        return jdbc.queryForObject(
                """
                SELECT pg_try_advisory_xact_lock(:lockKey) AS locked,
                       statement_timestamp() AS snapshot_at
                """,
                Map.of("lockKey", period.lockKey()),
                (result, row) ->
                        new LockAttempt(
                                result.getBoolean("locked"),
                                instant(result.getObject("snapshot_at"))));
    }

    public record LockAttempt(boolean locked, Instant snapshotAt) {}

    public List<Long> findEligibleUserIds(ReportPeriod period, Instant effectiveEnd) {
        return jdbc.queryForList(
                """
                SELECT f.user_id
                FROM frames f
                JOIN users u ON u.id = f.user_id AND u.status = 'ACTIVE'
                WHERE f.created_at >= :startAt AND f.created_at < :endAt
                GROUP BY f.user_id
                HAVING COUNT(*) >= 3
                ORDER BY f.user_id
                """,
                periodParams(period, effectiveEnd),
                Long.class);
    }

    public List<ReportSource.Frame> findFrames(List<Long> userIds, Instant effectiveEnd) {
        return jdbc.query(
                """
                SELECT f.id,
                       f.user_id,
                       f.movie_id,
                       r.score,
                       f.created_at,
                       EXTRACT(YEAR FROM m.release_date)::int,
                       pf.code,
                       pf.display_name
                FROM frames f
                JOIN ratings r ON r.id = f.rating_id
                JOIN movies m ON m.id = f.movie_id
                LEFT JOIN popcorn_flavors pf ON pf.id = m.representative_flavor_id
                WHERE f.user_id IN (:userIds) AND f.created_at < :endAt
                ORDER BY f.user_id, f.created_at, f.id
                """,
                new MapSqlParameterSource("userIds", userIds)
                        .addValue("endAt", Timestamp.from(effectiveEnd)),
                (result, row) ->
                        new ReportSource.Frame(
                                result.getLong("id"),
                                result.getLong("user_id"),
                                result.getLong("movie_id"),
                                result.getBigDecimal("score"),
                                instant(result.getObject("created_at")),
                                (Integer) result.getObject(6),
                                result.getString("code"),
                                result.getString("display_name")));
    }

    public List<ReportSource.Genre> findGenres(List<Long> userIds, Instant effectiveEnd) {
        return jdbc.query(
                """
                SELECT f.id AS frame_id, g.code, g.name
                FROM frames f
                JOIN movie_genres mg ON mg.movie_id = f.movie_id
                JOIN genres g ON g.id = mg.genre_id
                WHERE f.user_id IN (:userIds) AND f.created_at < :endAt
                ORDER BY f.id, g.code
                """,
                new MapSqlParameterSource("userIds", userIds)
                        .addValue("endAt", Timestamp.from(effectiveEnd)),
                (result, row) ->
                        new ReportSource.Genre(
                                result.getLong("frame_id"),
                                result.getString("code"),
                                result.getString("name")));
    }

    public List<ReportSource.Country> findCountries(List<Long> userIds, Instant effectiveEnd) {
        return jdbc.query(
                """
                SELECT f.id AS frame_id, mc.country_code
                FROM frames f
                JOIN movie_countries mc ON mc.movie_id = f.movie_id
                WHERE f.user_id IN (:userIds) AND f.created_at < :endAt
                ORDER BY f.id, mc.country_code
                """,
                new MapSqlParameterSource("userIds", userIds)
                        .addValue("endAt", Timestamp.from(effectiveEnd)),
                (result, row) ->
                        new ReportSource.Country(
                                result.getLong("frame_id"), result.getString("country_code")));
    }

    public List<ReportSource.Person> findPeople(List<Long> userIds, Instant effectiveEnd) {
        return jdbc.query(
                """
                SELECT f.id AS frame_id, p.id AS person_id, p.name, p.profile_url, mp.role
                FROM frames f
                JOIN movie_people mp ON mp.movie_id = f.movie_id
                JOIN people p ON p.id = mp.person_id
                WHERE f.user_id IN (:userIds)
                  AND f.created_at < :endAt
                ORDER BY f.id, mp.role, mp.display_order NULLS LAST, p.id
                """,
                new MapSqlParameterSource("userIds", userIds)
                        .addValue("endAt", Timestamp.from(effectiveEnd)),
                (result, row) ->
                        new ReportSource.Person(
                                result.getLong("frame_id"),
                                result.getLong("person_id"),
                                result.getString("name"),
                                result.getString("profile_url"),
                                Role.valueOf(result.getString("role"))));
    }

    public Set<Long> findUsersWithReadyPreviousReport(List<Long> userIds, ReportPeriod previous) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        List<Long> result =
                jdbc.queryForList(
                        """
                        SELECT user_id
                        FROM taste_reports
                        WHERE user_id IN (:userIds)
                          AND year = :year
                          AND half = :half
                          AND status = 'READY'
                        """,
                        new MapSqlParameterSource("userIds", userIds)
                                .addValue("year", previous.year())
                                .addValue("half", previous.half().name()),
                        Long.class);
        return new HashSet<>(result);
    }

    public void upsertGenerating(
            long userId, ReportPeriod period, String content, Instant createdAt) {
        jdbc.update(
                """
                INSERT INTO taste_reports (user_id, year, half, status, content, created_at)
                VALUES (:userId, :year, :half, 'GENERATING', CAST(:content AS jsonb), :createdAt)
                ON CONFLICT (user_id, year, half) DO UPDATE
                SET status = 'GENERATING',
                    content = EXCLUDED.content,
                    created_at = EXCLUDED.created_at
                WHERE taste_reports.status = 'GENERATING'
                """,
                new MapSqlParameterSource("userId", userId)
                        .addValue("year", period.year())
                        .addValue("half", period.half().name())
                        .addValue("content", content)
                        .addValue("createdAt", Timestamp.from(createdAt)));
    }

    public int deleteIneligibleGenerating(ReportPeriod period, List<Long> eligibleUserIds) {
        String eligibleCondition =
                eligibleUserIds.isEmpty() ? "" : " AND user_id NOT IN (:eligibleUserIds)";
        MapSqlParameterSource parameters =
                new MapSqlParameterSource("year", period.year())
                        .addValue("half", period.half().name());
        if (!eligibleUserIds.isEmpty()) {
            parameters.addValue("eligibleUserIds", eligibleUserIds);
        }
        return jdbc.update(
                "DELETE FROM taste_reports WHERE year = :year AND half = :half"
                        + " AND status = 'GENERATING'"
                        + eligibleCondition,
                parameters);
    }

    public boolean hasReady(ReportPeriod period) {
        return countByStatus(period, "READY") > 0;
    }

    public Integer findCompletedPreparedCount(ReportPeriod period) {
        List<Integer> counts =
                jdbc.queryForList(
                        """
                        SELECT prepared_count
                        FROM taste_report_generation_runs
                        WHERE year = :year AND half = :half
                        """,
                        periodParams(period),
                        Integer.class);
        return counts.isEmpty() ? null : counts.getFirst();
    }

    public void recordCompletedFinalRun(
            ReportPeriod period, Instant snapshotAt, int preparedCount) {
        jdbc.update(
                """
                INSERT INTO taste_report_generation_runs (year, half, snapshot_at, prepared_count)
                VALUES (:year, :half, :snapshotAt, :preparedCount)
                ON CONFLICT (year, half) DO NOTHING
                """,
                new MapSqlParameterSource("year", period.year())
                        .addValue("half", period.half().name())
                        .addValue("snapshotAt", Timestamp.from(snapshotAt))
                        .addValue("preparedCount", preparedCount));
    }

    public boolean hasPreparedFinal(ReportPeriod period) {
        Long count =
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM taste_reports
                        WHERE year = :year
                          AND half = :half
                          AND status = 'GENERATING'
                          AND content #>> '{generation,finalSnapshot}' = 'true'
                          AND content #>> '{generation,cutoffAt}' = :cutoffAt
                        """,
                        periodParams(period),
                        Long.class);
        return count != null && count > 0;
    }

    public List<ReportPeriod> findPreparedFinalPeriods() {
        return jdbc.getJdbcTemplate()
                .query(
                        """
                        SELECT DISTINCT year, half
                        FROM taste_reports
                        WHERE status = 'GENERATING'
                          AND content #>> '{generation,finalSnapshot}' = 'true'
                        ORDER BY year, half
                        """,
                        (result, row) ->
                                new ReportPeriod(
                                        result.getInt("year"),
                                        ReportHalf.valueOf(result.getString("half"))));
    }

    public int publishPreparedFinal(ReportPeriod period) {
        return jdbc.update(
                """
                UPDATE taste_reports
                SET status = 'READY'
                WHERE year = :year
                  AND half = :half
                  AND status = 'GENERATING'
                  AND content #>> '{generation,finalSnapshot}' = 'true'
                  AND content #>> '{generation,cutoffAt}' = :cutoffAt
                """,
                periodParams(period));
    }

    private long countByStatus(ReportPeriod period, String status) {
        Long count =
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*) FROM taste_reports
                        WHERE year = :year AND half = :half AND status = :status
                        """,
                        new MapSqlParameterSource("year", period.year())
                                .addValue("half", period.half().name())
                                .addValue("status", status),
                        Long.class);
        return count == null ? 0 : count;
    }

    private Map<String, Object> periodParams(ReportPeriod period, Instant effectiveEnd) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("startAt", Timestamp.from(period.startInclusive()));
        parameters.put("endAt", Timestamp.from(effectiveEnd));
        return parameters;
    }

    private Map<String, Object> periodParams(ReportPeriod period) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("year", period.year());
        parameters.put("half", period.half().name());
        parameters.put("cutoffAt", period.cutoffExclusive().toString());
        return parameters;
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("Unsupported timestamp type: " + value.getClass());
    }
}
