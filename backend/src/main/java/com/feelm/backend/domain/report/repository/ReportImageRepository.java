package com.feelm.backend.domain.report.repository;

import com.feelm.backend.domain.report.model.ReportHalf;
import com.feelm.backend.domain.report.model.ReportPeriod;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReportImageRepository {

    private final JdbcTemplate jdbc;

    public ReportImageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ReportSource> lockOwned(long userId, long reportId) {
        List<ReportSource> rows =
                jdbc.query(
                        """
                        SELECT tr.id, tr.year, tr.half, tr.status
                        FROM taste_reports tr
                        WHERE tr.user_id = ? AND tr.id = ?
                        FOR UPDATE
                        """,
                        (result, row) -> mapSource(result),
                        userId,
                        reportId);
        return rows.stream().findFirst();
    }

    public Optional<ImageRow> findByReportId(long reportId) {
        List<ImageRow> rows =
                jdbc.query(
                        """
                        SELECT id, report_id, storage_key, created_at
                        FROM report_images
                        WHERE report_id = ?
                        ORDER BY id
                        LIMIT 1
                        """,
                        (result, row) -> mapImage(result),
                        reportId);
        return rows.stream().findFirst();
    }

    public Optional<ImageRow> findById(long imageId) {
        List<ImageRow> rows =
                jdbc.query(
                        """
                        SELECT id, report_id, storage_key, created_at
                        FROM report_images
                        WHERE id = ?
                        """,
                        (result, row) -> mapImage(result),
                        imageId);
        return rows.stream().findFirst();
    }

    public long insert(long reportId, String storageKey, Instant createdAt) {
        Long id =
                jdbc.queryForObject(
                        """
                        INSERT INTO report_images (report_id, storage_key, format, created_at)
                        VALUES (?, ?, 'PNG', ?)
                        RETURNING id
                        """,
                        Long.class,
                        reportId,
                        storageKey,
                        Timestamp.from(createdAt));
        if (id == null) {
            throw new IllegalStateException("Report image id was not returned");
        }
        return id;
    }

    public void touch(long imageId, Instant createdAt) {
        int updated =
                jdbc.update(
                        "UPDATE report_images SET created_at = ? WHERE id = ?",
                        Timestamp.from(createdAt),
                        imageId);
        if (updated != 1) {
            throw new IllegalStateException("Report image metadata disappeared while restoring");
        }
    }

    private ReportSource mapSource(ResultSet result) throws SQLException {
        return new ReportSource(
                result.getLong("id"),
                new ReportPeriod(
                        result.getInt("year"), ReportHalf.valueOf(result.getString("half"))),
                result.getString("status"));
    }

    private ImageRow mapImage(ResultSet result) throws SQLException {
        return new ImageRow(
                result.getLong("id"),
                result.getLong("report_id"),
                result.getString("storage_key"),
                instant(result.getObject("created_at")));
    }

    private Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("Unsupported timestamp type: " + value);
    }

    public record ReportSource(long id, ReportPeriod period, String status) {}

    public record ImageRow(long id, long reportId, String storageKey, Instant createdAt) {}
}
