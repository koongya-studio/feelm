package com.feelm.backend.domain.report.repository;

import com.feelm.backend.domain.report.model.ReportPeriod;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ReportManualRepository {

    private final JdbcTemplate jdbc;

    public boolean tryLock(long userId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, -userId));
    }

    public String filmState(long userId) {
        return jdbc.queryForObject(
                """
                SELECT COALESCE(jsonb_agg(jsonb_build_array(
                    f.id, f.movie_id, f.rating_id, r.score, r.comment, r.visibility, r.is_spoiler
                ) ORDER BY f.id), '[]'::jsonb)::text
                FROM frames f JOIN ratings r ON r.id = f.rating_id
                WHERE f.user_id = ?
                """,
                String.class,
                userId);
    }

    public int frameCount(long userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM frames WHERE user_id = ?", Integer.class, userId);
    }

    public Optional<Previous> latest(long userId) {
        return jdbc
                .query(
                        """
                SELECT id, source_fingerprint, source_frames::text, content::text, created_at, issued_at
                FROM taste_reports WHERE user_id = ? AND source_fingerprint IS NOT NULL
                ORDER BY created_at DESC, id DESC LIMIT 1
                """,
                        (r, n) ->
                                new Previous(
                                        r.getLong("id"),
                                        r.getString("source_fingerprint"),
                                        r.getString("source_frames"),
                                        r.getString("content"),
                                        r.getTimestamp("created_at").toInstant(),
                                        r.getTimestamp("issued_at") == null
                                                ? null
                                                : r.getTimestamp("issued_at").toInstant()),
                        userId)
                .stream()
                .findFirst();
    }

    public List<Long> eligibleUsers() {
        return jdbc.queryForList(
                """
                SELECT f.user_id FROM frames f JOIN users u ON u.id = f.user_id
                WHERE u.status = 'ACTIVE' GROUP BY f.user_id HAVING COUNT(*) >= 20
                ORDER BY f.user_id
                """,
                Long.class);
    }

    public java.util.Map<Long, String> previousSources(List<Long> userIds) {
        if (userIds.isEmpty()) return java.util.Map.of();
        return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
                        .query(
                                """
                SELECT DISTINCT ON (user_id) user_id, source_frames::text AS source
                FROM taste_reports WHERE user_id IN (:userIds) AND source_fingerprint IS NOT NULL
                ORDER BY user_id, created_at DESC, id DESC
                """,
                                java.util.Map.of("userIds", userIds),
                                (r, n) ->
                                        java.util.Map.entry(
                                                r.getLong("user_id"), r.getString("source")))
                        .stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        java.util.Map.Entry::getKey,
                                        java.util.Map.Entry::getValue));
    }

    public long insert(
            long userId,
            ReportPeriod period,
            String content,
            String fingerprint,
            String source,
            Instant now) {
        return jdbc.queryForObject(
                """
                INSERT INTO taste_reports
                    (user_id, year, half, status, content, source_fingerprint, source_frames, created_at)
                VALUES (?, ?, ?, 'READY', CAST(? AS jsonb), ?, CAST(? AS jsonb), ?)
                RETURNING id
                """,
                Long.class,
                userId,
                period.year(),
                period.half().name(),
                content,
                fingerprint,
                source,
                Timestamp.from(now));
    }

    public record Previous(
            long id,
            String fingerprint,
            String source,
            String content,
            Instant createdAt,
            Instant issuedAt) {}
}
