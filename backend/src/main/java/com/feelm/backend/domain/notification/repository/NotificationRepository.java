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
import java.util.Optional;
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

    /**
     * 파티 초대 알림을 만든다. 초대받은 사람이 파티 초대 알림을 껐으면(API-017 {@code PARTY_INVITE}) 만들지 않고 비어 있는 값을
     * 돌려준다(S15P21E106-857).
     *
     * <p>수신 여부를 같은 INSERT 문에서 판정해 조회와 삽입 사이에 설정이 바뀌는 틈을 두지 않는다. 설정 행이 없으면 기본값(켜짐)으로 본다.
     */
    public Optional<Long> createPartyInvitation(
            long userId, long invitationId, Instant notifiedAt, Instant expiresAt) {
        return jdbc.sql(
                        """
                        INSERT INTO notifications(
                            user_id, type, source_id, target_type, target_id,
                            title, body, notified_at, expires_at
                        )
                        SELECT
                            :userId, 'PARTY_INVITE', :invitationId, 'PARTY_INVITATION',
                            :invitationId, '파티팝콘 초대', '새로운 파티팝콘 초대가 도착했어요.',
                            :notifiedAt, :expiresAt
                        WHERE NOT EXISTS (
                            SELECT 1 FROM notification_settings
                            WHERE user_id = :userId AND NOT party_invite_enabled
                        )
                        ON CONFLICT (type, source_id) DO UPDATE SET
                            updated_at = notifications.updated_at
                        RETURNING id
                        """)
                .param("userId", userId)
                .param("invitationId", invitationId)
                .param("notifiedAt", offset(notifiedAt))
                .param("expiresAt", offset(expiresAt))
                .query(Long.class)
                .optional();
    }

    /**
     * 이번에 공개한 리포트마다 주인에게 리포트 발급 알림을 만들고 만든 수를 돌려준다(S15P21E106-858).
     *
     * <p>활성 계정이 아니거나 리포트 발급 알림을 끈 사용자(API-017 {@code REPORT_ISSUED})는 건너뛴다. 설정 행이 없으면 기본값(켜짐)으로 본다.
     * 같은 리포트는 UK(type, source_id)로 한 번만 만든다.
     *
     * <p>주인 계정 행을 공유 잠금으로 읽는다. 탈퇴(API-013)는 계정 행을 잠그고 알림을 지우는데, 잠그지 않으면 탈퇴가 커밋되기 전의 {@code ACTIVE}를
     * 보고 넣은 알림이 탈퇴의 삭제를 비켜 가 남는다. 잠그면 탈퇴 커밋을 기다린 뒤 상태를 다시 보고 건너뛴다. 교착을 막으려고 계정 번호 순으로 잠근다.
     */
    public int createReportIssued(
            List<Long> reportIds, String body, Instant notifiedAt, Instant expiresAt) {
        if (reportIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql(
                        """
                        INSERT INTO notifications(
                            user_id, type, source_id, target_type, target_id,
                            title, body, notified_at, expires_at
                        )
                        SELECT
                            report.user_id, 'REPORT_ISSUED', report.id, 'TASTE_REPORT',
                            report.id, '취향 리포트 발급', :body, :notifiedAt, :expiresAt
                        FROM taste_reports report
                        JOIN users owner ON owner.id = report.user_id AND owner.status = 'ACTIVE'
                        WHERE report.id IN (:reportIds)
                          AND NOT EXISTS (
                              SELECT 1 FROM notification_settings setting
                              WHERE setting.user_id = report.user_id
                                AND NOT setting.report_issued_enabled
                          )
                        ORDER BY owner.id
                        FOR SHARE OF owner
                        ON CONFLICT (type, source_id) DO NOTHING
                        """)
                .param("reportIds", reportIds)
                .param("body", body)
                .param("notifiedAt", offset(notifiedAt))
                .param("expiresAt", offset(expiresAt))
                .update();
    }

    public long createPartyInvitationAccepted(
            long ownerId,
            long invitationId,
            long partyId,
            String inviteeNickname,
            Instant notifiedAt,
            Instant expiresAt) {
        return jdbc.sql(
                        """
                        INSERT INTO notifications(
                            user_id, type, source_id, target_type, target_id,
                            title, body, notified_at, expires_at
                        ) VALUES (
                            :ownerId, 'PARTY_INVITE_ACCEPTED', :invitationId, 'PARTY',
                            :partyId, '파티팝콘 초대 수락', :body,
                            :notifiedAt, :expiresAt
                        )
                        ON CONFLICT (type, source_id) DO UPDATE SET
                            updated_at = notifications.updated_at
                        RETURNING id
                        """)
                .param("ownerId", ownerId)
                .param("invitationId", invitationId)
                .param("partyId", partyId)
                .param("body", inviteeNickname + " 님이 파티팝콘에 참여했어요.")
                .param("notifiedAt", offset(notifiedAt))
                .param("expiresAt", offset(expiresAt))
                .query(Long.class)
                .single();
    }

    public long upsertWatchConfirmation(
            long userId, long watchRecordId, Instant notifiedAt, Instant expiresAt) {
        return jdbc.sql(
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
                        RETURNING id
                        """)
                .param("userId", userId)
                .param("watchRecordId", watchRecordId)
                .param("notifiedAt", offset(notifiedAt))
                .param("expiresAt", offset(expiresAt))
                .query(Long.class)
                .single();
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
                          AND (type <> 'WATCH_CONFIRM' OR NOT EXISTS (
                              SELECT 1 FROM watch_records watch
                              WHERE watch.id = notifications.source_id
                                AND watch.user_id = notifications.user_id
                                AND (watch.status <> 'LINK_CLICKED' OR EXISTS (
                                    SELECT 1 FROM ratings rating
                                    WHERE rating.user_id = watch.user_id
                                      AND rating.movie_id = watch.movie_id
                                ))
                          ))
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
                          AND (type <> 'WATCH_CONFIRM' OR NOT EXISTS (
                              SELECT 1 FROM watch_records watch
                              WHERE watch.id = notifications.source_id
                                AND watch.user_id = notifications.user_id
                                AND (watch.status <> 'LINK_CLICKED' OR EXISTS (
                                    SELECT 1 FROM ratings rating
                                    WHERE rating.user_id = watch.user_id
                                      AND rating.movie_id = watch.movie_id
                                ))
                          ))
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
                                  AND (type <> 'WATCH_CONFIRM' OR NOT EXISTS (
                                      SELECT 1 FROM watch_records watch
                                      WHERE watch.id = notifications.source_id
                                        AND watch.user_id = notifications.user_id
                                        AND (watch.status <> 'LINK_CLICKED' OR EXISTS (
                                            SELECT 1 FROM ratings rating
                                            WHERE rating.user_id = watch.user_id
                                              AND rating.movie_id = watch.movie_id
                                        ))
                                  ))
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
                          AND (type <> 'WATCH_CONFIRM' OR NOT EXISTS (
                              SELECT 1 FROM watch_records watch
                              WHERE watch.id = notifications.source_id
                                AND watch.user_id = notifications.user_id
                                AND (watch.status <> 'LINK_CLICKED' OR EXISTS (
                                    SELECT 1 FROM ratings rating
                                    WHERE rating.user_id = watch.user_id
                                      AND rating.movie_id = watch.movie_id
                                ))
                          ))
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
                                          AND (type <> 'WATCH_CONFIRM' OR NOT EXISTS (
                                              SELECT 1 FROM watch_records watch
                                              WHERE watch.id = notifications.source_id
                                                AND watch.user_id = notifications.user_id
                                                AND (watch.status <> 'LINK_CLICKED' OR EXISTS (
                                                    SELECT 1 FROM ratings rating
                                                    WHERE rating.user_id = watch.user_id
                                                      AND rating.movie_id = watch.movie_id
                                                ))
                                          ))
                                      )
                                  )
                                """)
                        .param("dismissedAt", offset(dismissedAt))
                        .param("notificationId", notificationId)
                        .param("userId", userId)
                        .update()
                == 1;
    }

    public Optional<Long> close(
            long userId, NotificationType type, long sourceId, Instant closedAt) {
        return jdbc.sql(
                        """
                        UPDATE notifications
                        SET read_at = COALESCE(read_at, :closedAt),
                            closed_at = COALESCE(closed_at, :closedAt),
                            updated_at = now()
                        WHERE user_id = :userId AND type = :type AND source_id = :sourceId
                        RETURNING id
                        """)
                .param("closedAt", offset(closedAt))
                .param("userId", userId)
                .param("type", type.name())
                .param("sourceId", sourceId)
                .query(Long.class)
                .optional();
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
