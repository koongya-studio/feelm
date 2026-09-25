package com.feelm.backend.domain.social.repository;

import com.feelm.backend.domain.social.cursor.PublicReviewCursor;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** API-032의 접근 조건과 공개 한줄평 페이지를 PostgreSQL에서 읽는 projection repository. */
@Repository
@RequiredArgsConstructor
public class PublicReviewQueryRepository {

    public static final String NON_BLANK_COMMENT_PREDICATE =
            "btrim(r.comment, U&'\\0009\\000A\\000B\\000C\\000D\\0020\\0085\\00A0\\1680"
                    + "\\2000\\2001\\2002\\2003\\2004\\2005\\2006\\2007\\2008\\2009"
                    + "\\200A\\2028\\2029\\202F\\205F\\3000') <> ''";

    private final JdbcClient jdbc;

    /** 접근 판정과 페이지를 PostgreSQL READ COMMITTED의 동일한 statement snapshot에서 읽는다. */
    public PageSnapshot findPageSnapshot(
            long movieId,
            long viewerId,
            PublicReviewCursor cursor,
            int limit,
            boolean validCursor) {
        String sql = pageSql(cursor != null);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("movieId", movieId);
        parameters.put("viewerId", viewerId);
        parameters.put("validCursor", validCursor);

        if (cursor != null) {
            parameters.put("cursorCreatedAt", offset(cursor.createdAt()));
            parameters.put("cursorRatingId", cursor.ratingId());
        }
        parameters.put("limit", limit);

        List<SnapshotRow> rows =
                jdbc.sql(sql).params(parameters).query(this::mapSnapshotRow).list();
        AccessContext access = rows.getFirst().access();
        return new PageSnapshot(
                access,
                rows.stream().map(SnapshotRow::review).filter(review -> review != null).toList());
    }

    /** 실제 조회와 EXPLAIN 검증이 같은 SQL shape를 공유한다. */
    public static String pageSql(boolean withCursor) {
        StringBuilder sql =
                new StringBuilder(
                        """
                        WITH access AS (
                            SELECT EXISTS (
                                       SELECT 1 FROM users
                                       WHERE id = :viewerId AND status = 'ACTIVE'
                                   ) AS viewer_active,
                                   EXISTS (
                                       SELECT 1 FROM movies WHERE id = :movieId
                                   ) AS movie_exists
                        )
                        SELECT access.viewer_active, access.movie_exists,
                               page.rating_id, page.user_id, page.nickname, page.score,
                               page.comment, page.is_spoiler, page.created_at
                        FROM access
                        LEFT JOIN LATERAL (
                        SELECT r.id AS rating_id, r.user_id, u.nickname, r.score,
                               r.comment, r.is_spoiler, r.created_at
                        FROM ratings r
                        JOIN users u ON u.id = r.user_id AND u.status = 'ACTIVE'
                        WHERE access.viewer_active AND access.movie_exists AND :validCursor
                          AND r.movie_id = :movieId
                          AND r.comment IS NOT NULL
                          AND %s
                          AND (r.visibility = 'PUBLIC'
                               OR (r.user_id = :viewerId AND r.visibility = 'PRIVATE'))
                          -- 작성자의 한줄평 공개 범위(S15P21E106-856). 본인 한줄평에는 적용하지 않는다.
                          AND (u.review_visibility = 'PUBLIC' OR r.user_id = :viewerId)
                          -- FR-39 양방향 차단 의미를 복제한다. user 도메인 공통 SQL 조각 제공 시 교체한다.
                          AND NOT EXISTS (
                              SELECT 1
                              FROM user_blocks ub
                              WHERE (ub.blocker_id = :viewerId AND ub.blocked_id = r.user_id)
                                 OR (ub.blocker_id = r.user_id AND ub.blocked_id = :viewerId)
                          )
                        """
                                .formatted(NON_BLANK_COMMENT_PREDICATE));
        if (withCursor) {
            sql.append(
                    " AND (r.created_at < :cursorCreatedAt"
                            + " OR (r.created_at = :cursorCreatedAt AND r.id < :cursorRatingId))");
        }
        return sql.append(
                        " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit"
                                + ") page ON true"
                                + " ORDER BY page.created_at DESC NULLS LAST,"
                                + " page.rating_id DESC NULLS LAST")
                .toString();
    }

    private SnapshotRow mapSnapshotRow(ResultSet row, int rowNumber) throws SQLException {
        AccessContext access =
                new AccessContext(row.getBoolean("viewer_active"), row.getBoolean("movie_exists"));
        long ratingId = row.getLong("rating_id");
        if (row.wasNull()) {
            return new SnapshotRow(access, null);
        }
        return new SnapshotRow(
                access,
                new PublicReviewRow(
                        ratingId,
                        row.getLong("user_id"),
                        row.getString("nickname"),
                        row.getBigDecimal("score"),
                        row.getString("comment"),
                        row.getBoolean("is_spoiler"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()));
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public record AccessContext(boolean viewerActive, boolean movieExists) {}

    public record PageSnapshot(AccessContext access, List<PublicReviewRow> reviews) {}

    private record SnapshotRow(AccessContext access, PublicReviewRow review) {}

    public record PublicReviewRow(
            long ratingId,
            long userId,
            String nickname,
            BigDecimal score,
            String comment,
            boolean spoiler,
            Instant createdAt) {}
}
