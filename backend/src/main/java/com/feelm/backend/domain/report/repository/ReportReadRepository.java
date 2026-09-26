package com.feelm.backend.domain.report.repository;

import com.feelm.backend.domain.report.model.ReportHalf;
import com.feelm.backend.domain.report.model.ReportPeriod;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReportReadRepository {

    private final JdbcTemplate jdbc;

    public ReportReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ReportRow> findAllOwned(long userId) {
        return jdbc.query(
                """
                SELECT id, year, half, status, content::text AS content,
                       created_at, issued_at, source_fingerprint
                FROM taste_reports
                WHERE user_id = ?
                ORDER BY year DESC, half DESC, created_at DESC, id DESC
                """,
                (result, row) -> map(result),
                userId);
    }

    public Optional<ReportRow> findOwned(long userId, long reportId) {
        List<ReportRow> rows =
                jdbc.query(
                        """
                        SELECT id, year, half, status, content::text AS content,
                               created_at, issued_at, source_fingerprint
                        FROM taste_reports
                        WHERE user_id = ? AND id = ?
                        """,
                        (result, row) -> map(result),
                        userId,
                        reportId);
        return rows.stream().findFirst();
    }

    public boolean hasCompletedFinalRun(ReportPeriod period) {
        Long count =
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM taste_report_generation_runs
                        WHERE year = ? AND half = ?
                        """,
                        Long.class,
                        period.year(),
                        period.half().name());
        return count != null && count > 0;
    }

    /** Latin-script names for the given people; ids without one are absent from the map. */
    public Map<Long, String> findOriginalNames(Collection<Long> personIds) {
        if (personIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(personIds.size(), "?"));
        return jdbc
                .query(
                        "SELECT id, original_name FROM people"
                                + " WHERE original_name IS NOT NULL AND id IN ("
                                + placeholders
                                + ")",
                        (result, row) ->
                                Map.entry(result.getLong("id"), result.getString("original_name")),
                        personIds.toArray())
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public int issueIfUnissued(long userId, long reportId, Instant now) {
        return jdbc.update(
                """
                UPDATE taste_reports
                SET issued_at = ?
                WHERE user_id = ? AND id = ?
                  AND status = 'READY' AND issued_at IS NULL
                """,
                Timestamp.from(now),
                userId,
                reportId);
    }

    private ReportRow map(java.sql.ResultSet result) throws java.sql.SQLException {
        return new ReportRow(
                result.getLong("id"),
                new ReportPeriod(
                        result.getInt("year"), ReportHalf.valueOf(result.getString("half"))),
                result.getString("status"),
                result.getString("content"),
                instant(result.getObject("created_at")),
                instant(result.getObject("issued_at")),
                result.getString("source_fingerprint"));
    }

    private Instant instant(Object value) {
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
        throw new IllegalStateException("Unsupported timestamp type: " + value.getClass());
    }

    public record ReportRow(
            long id,
            ReportPeriod period,
            String status,
            String content,
            Instant createdAt,
            Instant issuedAt,
            String sourceFingerprint) {
        public boolean manual() {
            return sourceFingerprint != null;
        }
    }
}
