package com.feelm.backend.domain.notification.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WebPushSubscriptionRepository {

    private final JdbcClient jdbc;

    public long upsert(
            long userId,
            String endpoint,
            String fingerprint,
            String p256dh,
            String auth,
            Instant expirationAt) {
        return jdbc.sql(
                        """
                        INSERT INTO web_push_subscriptions(
                            user_id, endpoint, endpoint_fingerprint, p256dh_key, auth_secret,
                            expiration_at, active, capability_version
                        ) VALUES (
                            :userId, :endpoint, :fingerprint, :p256dh, :auth, :expirationAt, true, 1
                        )
                        ON CONFLICT (endpoint_fingerprint) DO UPDATE SET
                            user_id = EXCLUDED.user_id,
                            endpoint = EXCLUDED.endpoint,
                            p256dh_key = EXCLUDED.p256dh_key,
                            auth_secret = EXCLUDED.auth_secret,
                            expiration_at = EXCLUDED.expiration_at,
                            active = true,
                            capability_version = web_push_subscriptions.capability_version + 1,
                            deactivated_at = NULL,
                            updated_at = now()
                        RETURNING id
                        """)
                .param("userId", userId)
                .param("endpoint", endpoint)
                .param("fingerprint", fingerprint)
                .param("p256dh", p256dh)
                .param("auth", auth)
                .param("expirationAt", expirationAt == null ? null : offset(expirationAt))
                .query(Long.class)
                .single();
    }

    public Optional<SubscriptionOwner> findOwnerByFingerprint(String fingerprint) {
        return jdbc.sql(
                        """
                        SELECT id, user_id, active, capability_version
                        FROM web_push_subscriptions
                        WHERE endpoint_fingerprint = :fingerprint
                        """)
                .param("fingerprint", fingerprint)
                .query(this::mapOwner)
                .optional();
    }

    public boolean deactivate(long userId, long subscriptionId, Instant now) {
        return jdbc.sql(
                                """
                                UPDATE web_push_subscriptions
                                SET endpoint = NULL, p256dh_key = NULL, auth_secret = NULL,
                                    active = false, deactivated_at = :now, updated_at = now()
                                WHERE id = :subscriptionId AND user_id = :userId AND active
                                """)
                        .param("subscriptionId", subscriptionId)
                        .param("userId", userId)
                        .param("now", offset(now))
                        .update()
                == 1;
    }

    public int deactivateExpired(Instant now) {
        return jdbc.sql(
                        """
                        WITH expired AS (
                            UPDATE web_push_subscriptions
                            SET endpoint = NULL, p256dh_key = NULL, auth_secret = NULL,
                                active = false, deactivated_at = :now, updated_at = now()
                            WHERE active AND expiration_at IS NOT NULL AND expiration_at <= :now
                            RETURNING id
                        ), canceled AS (
                            UPDATE web_push_deliveries d
                            SET status = 'CANCELED', claimed_at = NULL, claim_token = NULL,
                                finished_at = :now, failure_code = 'SUBSCRIPTION_EXPIRED',
                                updated_at = now()
                            WHERE d.subscription_id IN (SELECT id FROM expired)
                              AND d.status IN ('PENDING', 'PROCESSING')
                            RETURNING d.id
                        )
                        SELECT count(*)::integer FROM expired
                        """)
                .param("now", offset(now))
                .query(Integer.class)
                .single();
    }

    public boolean deactivateForDelivery(long subscriptionId, long capabilityVersion, Instant now) {
        return jdbc.sql(
                                """
                        WITH deactivated AS (
                            UPDATE web_push_subscriptions
                            SET endpoint = NULL, p256dh_key = NULL, auth_secret = NULL,
                                active = false, deactivated_at = :now, updated_at = now()
                            WHERE id = :subscriptionId AND active
                              AND capability_version = :capabilityVersion
                            RETURNING id
                        ), canceled AS (
                            UPDATE web_push_deliveries d
                            SET status = 'CANCELED', claimed_at = NULL, claim_token = NULL,
                                finished_at = :now, failure_code = 'SUBSCRIPTION_EXPIRED',
                                updated_at = now()
                            WHERE d.subscription_id IN (SELECT id FROM deactivated)
                              AND d.status IN ('PENDING', 'PROCESSING')
                            RETURNING d.id
                        )
                        SELECT count(*)::integer FROM deactivated
                        """)
                        .param("subscriptionId", subscriptionId)
                        .param("capabilityVersion", capabilityVersion)
                        .param("now", offset(now))
                        .query(Integer.class)
                        .single()
                == 1;
    }

    public void deactivateByUserId(long userId, Instant now) {
        jdbc.sql(
                        """
                        UPDATE web_push_subscriptions
                        SET endpoint = NULL, p256dh_key = NULL, auth_secret = NULL,
                            active = false, deactivated_at = :now, updated_at = now()
                        WHERE user_id = :userId AND active
                        """)
                .param("userId", userId)
                .param("now", offset(now))
                .update();
    }

    private SubscriptionOwner mapOwner(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SubscriptionOwner(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                resultSet.getBoolean("active"),
                resultSet.getLong("capability_version"));
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public record SubscriptionOwner(
            long subscriptionId, long userId, boolean active, long capabilityVersion) {}
}
