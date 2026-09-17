package com.feelm.backend.domain.watch.repository;

import com.feelm.backend.domain.watch.cursor.MyRatingCursor;
import com.feelm.backend.domain.watch.dto.request.MyRatingCriteria;
import com.feelm.backend.domain.watch.model.MyRatingItemType;
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

/** API-039 평가·미평가 합집합을 사용자 접근 판정과 동일한 PostgreSQL statement로 읽는다. */
@Repository
@RequiredArgsConstructor
public class MyRatingQueryRepository {

    private static final String NON_BLANK_COMMENT_PREDICATE =
            "btrim(r.comment, U&'\\0009\\000A\\000B\\000C\\000D\\0020\\0085\\00A0\\1680"
                    + "\\2000\\2001\\2002\\2003\\2004\\2005\\2006\\2007\\2008\\2009"
                    + "\\200A\\2028\\2029\\202F\\205F\\3000') <> ''";

    private static final String RATED_SELECT =
            """
            SELECT r.id AS item_id, 'RATED' AS item_type, 1 AS kind_rank,
                   r.id AS rating_id, m.id AS movie_id, m.title, m.poster_url,
                   r.score, r.comment, r.visibility, r.is_spoiler AS spoiler,
                   f.id AS frame_id, r.created_at,
                   r.created_at AS activity_at
            FROM ratings r
            JOIN movies m ON m.id = r.movie_id
            LEFT JOIN frames f ON f.rating_id = r.id
                              AND f.user_id = r.user_id AND f.movie_id = r.movie_id
            WHERE r.user_id = :userId
            """;

    private static final String UNRATED_SELECT =
            """
            SELECT w.id AS item_id, 'UNRATED' AS item_type, 0 AS kind_rank,
                   NULL::bigint AS rating_id, m.id AS movie_id, m.title, m.poster_url,
                   NULL::numeric AS score, NULL::varchar AS comment,
                   NULL::varchar AS visibility, NULL::boolean AS spoiler,
                   NULL::bigint AS frame_id, NULL::timestamptz AS created_at,
                   COALESCE(w.watched_at, w.rated_at, w.clicked_at) AS activity_at
            FROM watch_records w
            JOIN movies m ON m.id = w.movie_id
            WHERE w.user_id = :userId AND w.status = 'WATCHED_UNRATED'
              AND NOT EXISTS (
                  SELECT 1 FROM ratings r
                  WHERE r.user_id = w.user_id AND r.movie_id = w.movie_id
              )
            """;

    private final JdbcClient jdbc;

    public PageSnapshot findPageSnapshot(
            long userId, MyRatingCriteria criteria, MyRatingCursor cursor, boolean validCursor) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("userId", userId);
        parameters.put("validCursor", validCursor);
        parameters.put("limit", criteria.size() + 1);
        if (cursor != null) {
            parameters.put(
                    "cursorAt", cursor.activityAt() == null ? null : offset(cursor.activityAt()));
            parameters.put("cursorScore", cursor.score());
            parameters.put("cursorRank", cursor.itemType() == MyRatingItemType.RATED ? 1 : 0);
            parameters.put("cursorId", cursor.itemId());
        }
        List<SnapshotRow> rows =
                jdbc.sql(pageSql(criteria, cursor))
                        .params(parameters)
                        .query(this::mapSnapshotRow)
                        .list();
        return new PageSnapshot(
                rows.getFirst().viewerActive(),
                rows.stream().map(SnapshotRow::item).filter(item -> item != null).toList());
    }

    /** 실제 API SQL과 성능 검증이 같은 SQL 생성 경로를 사용한다. */
    public static String pageSql(MyRatingCriteria criteria, MyRatingCursor cursor) {
        String feed =
                switch (criteria.filter()) {
                    case RATED -> RATED_SELECT;
                    case COMMENTED -> RATED_SELECT + " AND " + NON_BLANK_COMMENT_PREDICATE;
                    case UNRATED -> UNRATED_SELECT;
                    case ALL -> RATED_SELECT + " UNION ALL " + UNRATED_SELECT;
                };
        String order = orderBy(criteria.sort());
        StringBuilder sql =
                new StringBuilder(
                                """
                        WITH access AS (
                            SELECT EXISTS (
                                SELECT 1 FROM users
                                WHERE id = :userId AND status = 'ACTIVE'
                            ) AS viewer_active
                        ), feed AS (
                        """)
                        .append(feed)
                        .append(
                                """
                                )
                                SELECT access.viewer_active,
                                       page.item_id, page.item_type, page.kind_rank,
                                       page.rating_id, page.movie_id, page.title, page.poster_url,
                                       page.score, page.comment, page.visibility, page.spoiler,
                                       page.frame_id, page.created_at, page.activity_at
                                FROM access
                                LEFT JOIN LATERAL (
                                    SELECT * FROM feed
                                    WHERE access.viewer_active AND :validCursor
                                """);
        if (cursor != null) {
            sql.append(" AND ").append(afterCursor(criteria.sort(), cursor));
        }
        sql.append(" ORDER BY ").append(order).append(" LIMIT :limit) page ON true");
        return sql.append(" ORDER BY ").append(order.replace("feed.", "page.")).toString();
    }

    private static String orderBy(MyRatingCriteria.Sort sort) {
        String tail = "feed.activity_at DESC NULLS LAST, feed.kind_rank DESC, feed.item_id DESC";
        return switch (sort) {
            case LATEST -> tail;
            case OLDEST ->
                    "feed.activity_at ASC NULLS LAST, feed.kind_rank DESC, feed.item_id DESC";
            case SCORE_DESC -> "feed.score DESC NULLS LAST, " + tail;
            case SCORE_ASC -> "feed.score ASC NULLS LAST, " + tail;
        };
    }

    private static String afterCursor(MyRatingCriteria.Sort sort, MyRatingCursor cursor) {
        String eventAfter = eventAfter(cursor.activityAt(), sort == MyRatingCriteria.Sort.OLDEST);
        if (sort == MyRatingCriteria.Sort.LATEST || sort == MyRatingCriteria.Sort.OLDEST) {
            return eventAfter;
        }
        if (cursor.score() == null) {
            return "(feed.score IS NULL AND " + eventAfter + ")";
        }
        String comparator = sort == MyRatingCriteria.Sort.SCORE_DESC ? "<" : ">";
        return "(feed.score "
                + comparator
                + " :cursorScore OR feed.score IS NULL OR (feed.score = :cursorScore AND "
                + eventAfter
                + "))";
    }

    private static String eventAfter(Instant activityAt, boolean oldest) {
        String tie =
                "(feed.kind_rank < :cursorRank OR"
                        + " (feed.kind_rank = :cursorRank AND feed.item_id < :cursorId))";
        if (activityAt == null) {
            return "(feed.activity_at IS NULL AND " + tie + ")";
        }
        String comparator = oldest ? ">" : "<";
        return "(feed.activity_at "
                + comparator
                + " :cursorAt OR feed.activity_at IS NULL OR"
                + " (feed.activity_at = :cursorAt AND "
                + tie
                + "))";
    }

    private SnapshotRow mapSnapshotRow(ResultSet row, int rowNumber) throws SQLException {
        boolean viewerActive = row.getBoolean("viewer_active");
        long itemId = row.getLong("item_id");
        if (row.wasNull()) {
            return new SnapshotRow(viewerActive, null);
        }
        return new SnapshotRow(
                viewerActive,
                new MyRatingRow(
                        itemId,
                        MyRatingItemType.valueOf(row.getString("item_type")),
                        nullableLong(row, "rating_id"),
                        row.getLong("movie_id"),
                        row.getString("title"),
                        row.getString("poster_url"),
                        row.getBigDecimal("score"),
                        row.getString("comment"),
                        row.getString("visibility"),
                        nullableBoolean(row, "spoiler"),
                        nullableLong(row, "frame_id"),
                        instant(row, "created_at"),
                        instant(row, "activity_at")));
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static Boolean nullableBoolean(ResultSet row, String column) throws SQLException {
        boolean value = row.getBoolean(column);
        return row.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public record PageSnapshot(boolean viewerActive, List<MyRatingRow> items) {}

    private record SnapshotRow(boolean viewerActive, MyRatingRow item) {}

    public record MyRatingRow(
            long itemId,
            MyRatingItemType itemType,
            Long ratingId,
            long movieId,
            String title,
            String posterUrl,
            BigDecimal score,
            String comment,
            String visibility,
            Boolean spoiler,
            Long frameId,
            Instant createdAt,
            Instant activityAt) {}
}
