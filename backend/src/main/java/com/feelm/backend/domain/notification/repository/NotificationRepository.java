package com.feelm.backend.domain.notification.repository;

import com.feelm.backend.domain.notification.cursor.NotificationCursor;
import com.feelm.backend.domain.notification.model.NotificationTargetType;
import com.feelm.backend.domain.notification.model.NotificationType;
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

@Repository
@RequiredArgsConstructor
public class NotificationRepository {

    private final JdbcClient jdbc;

    public boolean isActiveUser(long userId) {
        return jdbc.sql("SELECT id FROM users WHERE id = :userId AND status = 'ACTIVE'")
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .isPresent();
    }

    public void createPartyInvitation(
            long userId, long invitationId, Instant notifiedAt, Instant expiresAt) {
        jdbc.sql(
                        """
                        INSERT INTO notifications(
                            user_id, type, source_id, target_type, target_id,
                            title, body, notified_at, expires_at
                        ) VALUES (
                            :userId, 'PARTY_INVITE', :invitationId, 'PARTY_INVITATION',
                            :invitationId, '파티 모드 초대', '새로운 파티 초대가 도착했어요.',
                            :notifiedAt, :expiresAt
                        )
                        ON CONFLICT (type, source_id) DO NOTHING
                        """)
                .param("userId", userId)
                .param("invitationId", invitationId)
                .param("notifiedAt", offset(notifiedAt))
                .param("expiresAt", offset(expiresAt))
                .update();
    }

    public void upsertWatchConfirmation(
            long userId, long watchRecordId, Instant notifiedAt, Instant expiresAt) {
        jdbc.sql(
                        """
                        INSERT INTO notifications(
                            user_id, type, source_id, target_type, target_id,
                            title, body, notified_at, expires_at
                        ) VALUES (
                            :userId, 'WATCH_CONFIRM', :watchRecordId, 'WATCH_RECORD',
                            :watchRecordId, '영화 잘 보셨나요?', '감상 여부를 알려주세요.',
                            :notifiedAt, :expiresAt
                        )
                        ON CONFLICT (type, source_id) DO UPDATE SET
                            user_id = EXCLUDED.user_id,
                            target_type = EXCLUDED.target_type,
                            target_id = EXCLUDED.target_id,
                            title = EXCLUDED.title,
                            body = EXCLUDED.body,
                            notified_at = EXCLUDED.notified_at,
                            expires_at = EXCLUDED.expires_at,
                            read_at = NULL,
                            closed_at = NULL,
                            updated_at = now()
                        """)
                .param("userId", userId)
                .param("watchRecordId", watchRecordId)
                .param("notifiedAt", offset(notifiedAt))
                .param("expiresAt", offset(expiresAt))
                .update();
    }

    public List<NotificationRow> findPage(
            long userId, NotificationCursor cursor, Instant now, int limit) {
        StringBuilder sql =
                new StringBuilder(
                        """
                        SELECT id, type, title, body, target_type, target_id, read_at, notified_at
                        FROM notifications
                        WHERE user_id = :userId
                          AND closed_at IS NULL
                          AND dismissed_at IS NULL
                          AND notified_at <= :now
                          AND expires_at > :now
                        """);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("userId", userId);
        parameters.put("now", offset(now));
        parameters.put("limit", limit);
        if (cursor != null) {
            sql.append(
                    " AND (notified_at < :cursorAt"
                            + " OR (notified_at = :cursorAt AND id < :cursorId))");
            parameters.put("cursorAt", offset(cursor.notifiedAt()));
            parameters.put("cursorId", cursor.notificationId());
        }
        sql.append(" ORDER BY notified_at DESC, id DESC LIMIT :limit");
        return jdbc.sql(sql.toString()).params(parameters).query(this::mapRow).list();
    }

    public long countUnread(long userId, Instant now) {
        return jdbc.sql(
                        """
                        SELECT count(*)
                        FROM notifications
                        WHERE user_id = :userId
                          AND read_at IS NULL
                          AND closed_at IS NULL
                          AND dismissed_at IS NULL
                          AND notified_at <= :now
                          AND expires_at > :now
                        """)
                .param("userId", userId)
                .param("now", offset(now))
                .query(Long.class)
                .single();
    }

    public boolean markRead(long userId, long notificationId, Instant readAt) {
        return jdbc.sql(
                                """
                                UPDATE notifications
                                SET read_at = COALESCE(read_at, :readAt), updated_at = now()
                                WHERE id = :notificationId
                                  AND user_id = :userId
                                  AND closed_at IS NULL
                                  AND dismissed_at IS NULL
                                  AND notified_at <= :readAt
                                  AND expires_at > :readAt
                                """)
                        .param("readAt", offset(readAt))
                        .param("notificationId", notificationId)
                        .param("userId", userId)
                        .update()
                == 1;
    }

    public int markAllRead(long userId, Instant readAt) {
        return jdbc.sql(
                        """
                        UPDATE notifications
                        SET read_at = :readAt, updated_at = now()
                        WHERE user_id = :userId
                          AND read_at IS NULL
                          AND closed_at IS NULL
                          AND dismissed_at IS NULL
                          AND notified_at <= :readAt
                          AND expires_at > :readAt
                        """)
                .param("readAt", offset(readAt))
                .param("userId", userId)
                .update();
    }

    public boolean dismiss(long userId, long notificationId, Instant dismissedAt) {
        return jdbc.sql(
                                """
                                UPDATE notifications
                                SET dismissed_at = COALESCE(dismissed_at, :dismissedAt),
                                    updated_at = now()
                                WHERE id = :notificationId
                                  AND user_id = :userId
                                  AND (
                                      dismissed_at IS NOT NULL
                                      OR (
                                          closed_at IS NULL
                                          AND notified_at <= :dismissedAt
                                          AND expires_at > :dismissedAt
                                      )
                                  )
                                """)
                        .param("dismissedAt", offset(dismissedAt))
                        .param("notificationId", notificationId)
                        .param("userId", userId)
                        .update()
                == 1;
    }

    public void close(long userId, NotificationType type, long sourceId, Instant closedAt) {
        jdbc.sql(
                        """
                        UPDATE notifications
                        SET read_at = COALESCE(read_at, :closedAt),
                            closed_at = COALESCE(closed_at, :closedAt),
                            updated_at = now()
                        WHERE user_id = :userId AND type = :type AND source_id = :sourceId
                        """)
                .param("closedAt", offset(closedAt))
                .param("userId", userId)
                .param("type", type.name())
                .param("sourceId", sourceId)
                .update();
    }

    public void deleteByUserId(long userId) {
        jdbc.sql("DELETE FROM notifications WHERE user_id = :userId")
                .param("userId", userId)
                .update();
    }

    public int deleteExpired(Instant now) {
        return jdbc.sql("DELETE FROM notifications WHERE expires_at <= :now")
                .param("now", offset(now))
                .update();
    }

    private NotificationRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new NotificationRow(
                resultSet.getLong("id"),
                NotificationType.valueOf(resultSet.getString("type")),
                resultSet.getString("title"),
                resultSet.getString("body"),
                NotificationTargetType.valueOf(resultSet.getString("target_type")),
                resultSet.getLong("target_id"),
                instant(resultSet, "read_at"),
                instant(resultSet, "notified_at"));
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record NotificationRow(
            long notificationId,
            NotificationType type,
            String title,
            String body,
            NotificationTargetType targetType,
            long targetId,
            Instant readAt,
            Instant notifiedAt) {}
}
