package com.feelm.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.notification.config.WebPushProperties;
import com.feelm.backend.domain.notification.config.WebPushProperties.Mode;
import com.feelm.backend.domain.notification.dto.request.WebPushSubscriptionRequest;
import com.feelm.backend.domain.notification.repository.NotificationRepository;
import com.feelm.backend.domain.notification.repository.WebPushDeliveryRepository;
import com.feelm.backend.domain.notification.repository.WebPushSubscriptionRepository;
import com.feelm.backend.domain.notification.service.NotificationRetentionCleaner;
import com.feelm.backend.domain.notification.service.WebPushSubscriptionService;
import com.feelm.backend.support.SharedPostgresContractTest;
import com.interaso.webpush.VapidKeys;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
class WebPushSubscriptionServiceTest extends SharedPostgresContractTest {

    private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationRepository notifications;
    @Autowired WebPushSubscriptionRepository subscriptions;
    @Autowired WebPushDeliveryRepository deliveries;

    private WebPushSubscriptionService service;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (10,'backfill@example.test','backfill',DATE '2000-01-01',"
                        + "'OTHER','ACTIVE')");
        jdbc.update(
                "INSERT INTO notification_settings(user_id,rating_enabled,recommendation_enabled,"
                        + "marketing_enabled) VALUES (10,true,true,false)");
        service =
                new WebPushSubscriptionService(
                        properties(),
                        notifications,
                        subscriptions,
                        deliveries,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void registerDoesNotBackfillExistingNotifications() {
        notification("PARTY_INVITE", 1, NOW.minusSeconds(300), NOW.plusSeconds(600));
        notification("WATCH_CONFIRM", 2, NOW.minusSeconds(1), NOW.plusSeconds(600));
        notification("WATCH_CONFIRM", 3, NOW.plusSeconds(300), NOW.plusSeconds(900));
        VapidKeys keys = VapidKeys.generate();
        String publicKey =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(keys.getApplicationServerKey());
        String auth = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);

        service.register(
                10,
                new WebPushSubscriptionRequest(
                        "https://fcm.googleapis.com/fcm/send/backfill",
                        null,
                        new WebPushSubscriptionRequest.Keys(publicKey, auth)));

        assertThat(jdbc.queryForList("SELECT notification_id FROM web_push_deliveries", Long.class))
                .isEmpty();
    }

    @Test
    void expiredSubscriptionCleanupCancelsOldDeliveryBeforeReactivation() {
        Instant createdAt = Instant.now();
        long notification =
                notification(
                        "PARTY_INVITE",
                        4,
                        createdAt.minusSeconds(10),
                        createdAt.plusSeconds(3_600));
        VapidKeys keys = VapidKeys.generate();
        String publicKey =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(keys.getApplicationServerKey());
        String auth = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
        String endpoint = "https://fcm.googleapis.com/fcm/send/reactivated";
        long subscriptionId =
                service.register(
                                10,
                                new WebPushSubscriptionRequest(
                                        endpoint,
                                        null,
                                        new WebPushSubscriptionRequest.Keys(publicKey, auth)))
                        .subscriptionId();
        jdbc.update(
                "UPDATE web_push_subscriptions SET expiration_at=? WHERE id=?",
                Timestamp.from(createdAt.plusSeconds(60)),
                subscriptionId);
        assertThat(deliveries.enqueue(notification)).isOne();
        Instant cleanupAt = createdAt.plusSeconds(120);
        new NotificationRetentionCleaner(
                        notifications,
                        subscriptions,
                        deliveries,
                        Clock.fixed(cleanupAt, ZoneOffset.UTC))
                .deleteExpired();

        WebPushSubscriptionService reactivationService =
                new WebPushSubscriptionService(
                        properties(),
                        notifications,
                        subscriptions,
                        deliveries,
                        Clock.fixed(cleanupAt, ZoneOffset.UTC));
        long reactivatedId =
                reactivationService
                        .register(
                                10,
                                new WebPushSubscriptionRequest(
                                        endpoint,
                                        null,
                                        new WebPushSubscriptionRequest.Keys(publicKey, auth)))
                        .subscriptionId();

        assertThat(reactivatedId).isEqualTo(subscriptionId);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM web_push_deliveries WHERE notification_id=?",
                                String.class,
                                notification))
                .isEqualTo("CANCELED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT capability_version FROM web_push_subscriptions WHERE id=?",
                                Long.class,
                                subscriptionId))
                .isEqualTo(2L);
    }

    private long notification(String type, long sourceId, Instant notifiedAt, Instant expiresAt) {
        String targetType = type.equals("PARTY_INVITE") ? "PARTY_INVITATION" : "WATCH_RECORD";
        return jdbc.queryForObject(
                "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,title,body,"
                        + "notified_at,expires_at) VALUES (10,?,?,?,?, '제목','본문',?,?) RETURNING id",
                Long.class,
                type,
                sourceId,
                targetType,
                sourceId,
                Timestamp.from(notifiedAt),
                Timestamp.from(expiresAt));
    }

    private static WebPushProperties properties() {
        return new WebPushProperties(
                Mode.VAPID,
                "public",
                "private",
                "mailto:test@feelm.local",
                List.of("fcm.googleapis.com"),
                25,
                5,
                Duration.ofMinutes(1),
                Duration.ofHours(1),
                Duration.ofMinutes(2),
                Duration.ofSeconds(15));
    }
}
