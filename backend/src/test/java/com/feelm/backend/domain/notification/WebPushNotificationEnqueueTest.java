package com.feelm.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.notification.repository.WebPushSubscriptionRepository;
import com.feelm.backend.domain.notification.service.NotificationCommandService;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
class WebPushNotificationEnqueueTest extends SharedPostgresContractTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired WebPushSubscriptionRepository subscriptions;
    @Autowired NotificationCommandService commands;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        user(10, true);
        user(20, false);
    }

    @Test
    void partyInviteEnqueuesExactlyOnceForEveryActiveSubscription() {
        subscribe(10, "a", "1".repeat(64));
        subscribe(10, "b", "2".repeat(64));
        Instant createdAt = Instant.parse("2026-09-18T00:00:00Z");

        transactions.executeWithoutResult(
                ignored -> {
                    commands.createPartyInvitation(10, 100, createdAt);
                    commands.createPartyInvitation(10, 100, createdAt);
                });

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notifications WHERE type='PARTY_INVITE'",
                                Long.class))
                .isOne();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT title,body FROM notifications WHERE type='PARTY_INVITE'"))
                .containsEntry("title", "파티팝콘 초대")
                .containsEntry("body", "새로운 파티팝콘 초대가 도착했어요.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_push_deliveries", Long.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT min(next_attempt_at) FROM web_push_deliveries",
                                        Timestamp.class)
                                .toInstant())
                .isEqualTo(createdAt);
    }

    @Test
    void watchConfirmationIsStoredWithoutWebPushDelivery() {
        subscribe(10, "rating-on", "3".repeat(64));
        subscribe(20, "rating-off", "4".repeat(64));
        Instant dueAt = Instant.parse("2026-09-19T00:00:00Z");
        Instant expiresAt = dueAt.plusSeconds(600);

        transactions.executeWithoutResult(
                ignored -> {
                    commands.upsertWatchConfirmation(10, 101, dueAt, expiresAt);
                    commands.upsertWatchConfirmation(20, 102, dueAt, expiresAt);
                });

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notifications WHERE type='WATCH_CONFIRM'",
                                Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_push_deliveries", Long.class))
                .isZero();
    }

    @Test
    void partyInvitationAcceptanceEnqueuesPushForOwner() {
        subscribe(10, "owner", "5".repeat(64));
        Instant acceptedAt = Instant.parse("2026-09-19T00:00:00Z");

        transactions.executeWithoutResult(
                ignored ->
                        commands.createPartyInvitationAccepted(10, 103, 203, "초대받은사람", acceptedAt));

        assertThat(
                        jdbc.queryForMap(
                                "SELECT user_id,type,source_id,target_type,target_id,title,body"
                                        + " FROM notifications"))
                .containsEntry("user_id", 10L)
                .containsEntry("type", "PARTY_INVITE_ACCEPTED")
                .containsEntry("source_id", 103L)
                .containsEntry("target_type", "PARTY")
                .containsEntry("target_id", 203L)
                .containsEntry("title", "파티팝콘 초대 수락")
                .containsEntry("body", "초대받은사람 님이 파티팝콘에 참여했어요.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_push_deliveries", Long.class))
                .isOne();
    }

    private void user(long id, boolean ratingEnabled) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                id,
                "enqueue" + id + "@example.test",
                "enqueue" + id);
        jdbc.update(
                "INSERT INTO notification_settings(user_id,rating_enabled,recommendation_enabled,"
                        + "marketing_enabled) VALUES (?,?,true,false)",
                id,
                ratingEnabled);
    }

    private void subscribe(long userId, String suffix, String fingerprint) {
        subscriptions.upsert(
                userId,
                "https://fcm.googleapis.com/fcm/send/" + suffix,
                fingerprint,
                "key",
                "auth",
                null);
    }
}
