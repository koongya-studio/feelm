package com.feelm.backend.domain.notification.repository;

import com.feelm.backend.domain.notification.model.NotificationTargetType;
import com.feelm.backend.domain.notification.model.NotificationType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WebPushDeliveryRepository {

    private final JdbcClient jdbc;

    public int enqueue(long notificationId) {
        return jdbc.sql(
                        """
                        INSERT INTO web_push_deliveries(
                            notification_id, subscription_id, endpoint_fingerprint, status,
                            next_attempt_at
                        )
                        SELECT n.id, s.id, s.endpoint_fingerprint, 'PENDING', n.notified_at
                        FROM notifications n
                        JOIN web_push_subscriptions s ON s.user_id = n.user_id AND s.active
                        WHERE n.id = :notificationId
                          AND (s.expiration_at IS NULL OR s.expiration_at > now())
                          AND n.type IN ('PARTY_INVITE', 'PARTY_INVITE_ACCEPTED')
                        ON CONFLICT (notification_id, endpoint_fingerprint) DO NOTHING
                        """)
                .param("notificationId", notificationId)
                .update();
    }

    public int cancelForNotification(long notificationId, Instant now, String reason) {
        return cancel(
                "notification_id = :notificationId", "notificationId", notificationId, now, reason);
    }

    public int cancelForSubscription(long subscriptionId, Instant now, String reason) {
        return cancel(
                "subscription_id = :subscriptionId", "subscriptionId", subscriptionId, now, reason);
    }

    public int cancelForUser(long userId, Instant now, String reason) {
        return jdbc.sql(
                        """
                        UPDATE web_push_deliveries
                        SET status = 'CANCELED', claimed_at = NULL, claim_token = NULL,
                            finished_at = :now, failure_code = :reason, updated_at = now()
                        WHERE notification_id IN (
                            SELECT id FROM notifications WHERE user_id = :userId
                        )
                          AND status IN ('PENDING', 'PROCESSING')
                        """)
                .param("userId", userId)
                .param("now", offset(now))
                .param("reason", reason)
                .update();
    }

    public int cancelReadForUser(long userId, Instant now) {
        return jdbc.sql(
                        """
                        UPDATE web_push_deliveries
                        SET status = 'CANCELED', claimed_at = NULL, claim_token = NULL,
                            finished_at = :now, failure_code = 'NOTIFICATION_READ',
                            updated_at = now()
                        WHERE notification_id IN (
                            SELECT id FROM notifications
                            WHERE user_id = :userId AND read_at IS NOT NULL
                        )
                          AND status IN ('PENDING', 'PROCESSING')
                        """)
                .param("userId", userId)
                .param("now", offset(now))
                .update();
    }

    public int cancelIneligible(Instant now) {
        return jdbc.sql(
                        """
                        UPDATE web_push_deliveries d
                        SET status = 'CANCELED', claimed_at = NULL, claim_token = NULL,
                            finished_at = :now, failure_code = 'NO_LONGER_ELIGIBLE', updated_at = now()
                        FROM notifications n
                        WHERE d.notification_id = n.id
                          AND d.status IN ('PENDING', 'PROCESSING')
                          AND (
                              n.read_at IS NOT NULL OR n.closed_at IS NOT NULL
                              OR n.dismissed_at IS NOT NULL OR n.expires_at <= :now
                              OR NOT EXISTS (
                                  SELECT 1 FROM web_push_subscriptions s
                                  WHERE s.id = d.subscription_id AND s.active
                                    AND s.user_id = n.user_id
                                    AND (s.expiration_at IS NULL OR s.expiration_at > :now)
                              )
                              OR n.type NOT IN ('PARTY_INVITE', 'PARTY_INVITE_ACCEPTED')
                          )
                        """)
                .param("now", offset(now))
                .update();
    }

    public List<ClaimedDelivery> claimDue(
            Instant now, Duration leaseDuration, int batchSize, UUID claimToken) {
        return jdbc.sql(
                        """
                        WITH candidates AS (
                            SELECT d.id
                            FROM web_push_deliveries d
                            JOIN notifications n ON n.id = d.notification_id
                            JOIN web_push_subscriptions s
                              ON s.id = d.subscription_id AND s.user_id = n.user_id
                            WHERE (
                                    d.status = 'PENDING'
                                    OR (d.status = 'PROCESSING' AND d.claimed_at <= :staleBefore)
                                  )
                              AND d.next_attempt_at <= :now
                              AND n.notified_at <= :now AND n.expires_at > :now
                              AND n.read_at IS NULL AND n.closed_at IS NULL
                              AND n.dismissed_at IS NULL
                              AND s.active
                              AND (s.expiration_at IS NULL OR s.expiration_at > :now)
                              AND n.type IN ('PARTY_INVITE', 'PARTY_INVITE_ACCEPTED')
                            ORDER BY d.next_attempt_at, d.id
                            FOR UPDATE OF d SKIP LOCKED
                            LIMIT :batchSize
                        ), claimed AS (
                            UPDATE web_push_deliveries d
                            SET status = 'PROCESSING', attempt_count = d.attempt_count + 1,
                                claimed_at = :now, claim_token = :claimToken, updated_at = now()
                            FROM candidates c
                            WHERE d.id = c.id
                            RETURNING d.*
                        )
                        SELECT d.id delivery_id, d.notification_id, d.subscription_id,
                               d.attempt_count, d.claim_token,
                               n.type, n.title, n.body, n.target_type, n.target_id, n.expires_at,
                               s.endpoint, s.p256dh_key, s.auth_secret, s.capability_version
                        FROM claimed d
                        JOIN notifications n ON n.id = d.notification_id
                        JOIN web_push_subscriptions s ON s.id = d.subscription_id
                        ORDER BY d.next_attempt_at, d.id
                        """)
                .param("now", offset(now))
                .param("staleBefore", offset(now.minus(leaseDuration)))
                .param("batchSize", batchSize)
                .param("claimToken", claimToken)
                .query(this::mapClaimed)
                .list();
    }

    public boolean isClaimEligible(long deliveryId, UUID claimToken, Instant now) {
        return Boolean.TRUE.equals(
                jdbc.sql(
                                """
                                SELECT EXISTS (
                                    SELECT 1
                                    FROM web_push_deliveries d
                                    JOIN notifications n ON n.id = d.notification_id
                                    JOIN web_push_subscriptions s
                                      ON s.id = d.subscription_id AND s.user_id = n.user_id
                                    WHERE d.id = :deliveryId AND d.status = 'PROCESSING'
                                      AND d.claim_token = :claimToken
                                      AND n.notified_at <= :now AND n.expires_at > :now
                                      AND n.read_at IS NULL AND n.closed_at IS NULL
                                      AND n.dismissed_at IS NULL AND s.active
                                      AND (s.expiration_at IS NULL OR s.expiration_at > :now)
                                      AND n.type IN ('PARTY_INVITE', 'PARTY_INVITE_ACCEPTED')
                                )
                                """)
                        .param("deliveryId", deliveryId)
                        .param("claimToken", claimToken)
                        .param("now", offset(now))
                        .query(Boolean.class)
                        .single());
    }

    public boolean markSent(long deliveryId, UUID claimToken, Instant now, int httpStatus) {
        return finish(deliveryId, claimToken, now, "SENT", httpStatus, null) == 1;
    }

    public boolean markFailed(
            long deliveryId, UUID claimToken, Instant now, Integer httpStatus, String reason) {
        return finish(deliveryId, claimToken, now, "FAILED", httpStatus, reason) == 1;
    }

    public boolean scheduleRetry(
            long deliveryId,
            UUID claimToken,
            Instant nextAttemptAt,
            Integer httpStatus,
            String reason) {
        return jdbc.sql(
                                """
                                UPDATE web_push_deliveries
                                SET status = 'PENDING', next_attempt_at = :nextAttemptAt,
                                    claimed_at = NULL, claim_token = NULL,
                                    last_http_status = :httpStatus, failure_code = :reason,
                                    updated_at = now()
                                WHERE id = :deliveryId AND status = 'PROCESSING'
                                  AND claim_token = :claimToken
                                """)
                        .param("deliveryId", deliveryId)
                        .param("claimToken", claimToken)
                        .param("nextAttemptAt", offset(nextAttemptAt))
                        .param("httpStatus", httpStatus)
                        .param("reason", reason)
                        .update()
                == 1;
    }

    private int cancel(String predicate, String parameter, long value, Instant now, String reason) {
        return jdbc.sql(
                        "UPDATE web_push_deliveries SET status = 'CANCELED', claimed_at = NULL, "
                                + "claim_token = NULL, finished_at = :now, failure_code = :reason, "
                                + "updated_at = now() WHERE "
                                + predicate
                                + " AND status IN ('PENDING', 'PROCESSING')")
                .param(parameter, value)
                .param("now", offset(now))
                .param("reason", reason)
                .update();
    }

    private int finish(
            long deliveryId,
            UUID claimToken,
            Instant now,
            String status,
            Integer httpStatus,
            String reason) {
        return jdbc.sql(
                        """
                        UPDATE web_push_deliveries
                        SET status = :status, claimed_at = NULL, claim_token = NULL,
                            delivered_at = CASE WHEN :status = 'SENT' THEN :now ELSE delivered_at END,
                            finished_at = :now, last_http_status = :httpStatus,
                            failure_code = :reason, updated_at = now()
                        WHERE id = :deliveryId AND status = 'PROCESSING'
                          AND claim_token = :claimToken
                        """)
                .param("status", status)
                .param("now", offset(now))
                .param("httpStatus", httpStatus)
                .param("reason", reason)
                .param("deliveryId", deliveryId)
                .param("claimToken", claimToken)
                .update();
    }

    private ClaimedDelivery mapClaimed(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ClaimedDelivery(
                resultSet.getLong("delivery_id"),
                resultSet.getLong("notification_id"),
                resultSet.getLong("subscription_id"),
                resultSet.getInt("attempt_count"),
                resultSet.getObject("claim_token", UUID.class),
                NotificationType.valueOf(resultSet.getString("type")),
                resultSet.getString("title"),
                resultSet.getString("body"),
                NotificationTargetType.valueOf(resultSet.getString("target_type")),
                resultSet.getLong("target_id"),
                instant(resultSet, "expires_at"),
                resultSet.getString("endpoint"),
                resultSet.getString("p256dh_key"),
                resultSet.getString("auth_secret"),
                resultSet.getLong("capability_version"));
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    public record ClaimedDelivery(
            long deliveryId,
            long notificationId,
            long subscriptionId,
            int attemptCount,
            UUID claimToken,
            NotificationType type,
            String title,
            String body,
            NotificationTargetType targetType,
            long targetId,
            Instant expiresAt,
            String endpoint,
            String p256dh,
            String auth,
            long capabilityVersion) {}
}
