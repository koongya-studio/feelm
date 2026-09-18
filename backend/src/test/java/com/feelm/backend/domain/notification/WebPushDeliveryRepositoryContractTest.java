package com.feelm.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.notification.repository.WebPushDeliveryRepository;
import com.feelm.backend.domain.notification.repository.WebPushDeliveryRepository.ClaimedDelivery;
import com.feelm.backend.domain.notification.repository.WebPushSubscriptionRepository;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
class WebPushDeliveryRepositoryContractTest extends SharedPostgresContractTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired WebPushSubscriptionRepository subscriptions;
    @Autowired WebPushDeliveryRepository deliveries;

    private long subscriptionId;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (10,'delivery@example.test','delivery',DATE '2000-01-01',"
                        + "'OTHER','ACTIVE')");
        jdbc.update(
                "INSERT INTO notification_settings(user_id,rating_enabled,recommendation_enabled,"
                        + "marketing_enabled) VALUES (10,false,true,false)");
        subscriptionId =
                subscriptions.upsert(
                        10,
                        "https://fcm.googleapis.com/fcm/send/repository",
                        "d".repeat(64),
                        "key",
                        "auth",
                        null);
    }

    @Test
    void enqueuesOnlyPartyInvitesAndPreventsDuplicates() {
        Instant now = Instant.now();
        long invitation =
                notification("PARTY_INVITE", 1, now.minusSeconds(1), now.plusSeconds(600));
        long watch = notification("WATCH_CONFIRM", 2, now.minusSeconds(1), now.plusSeconds(600));

        assertThat(deliveries.enqueue(invitation)).isOne();
        assertThat(deliveries.enqueue(invitation)).isZero();
        assertThat(deliveries.enqueue(watch)).isZero();

        jdbc.update("UPDATE notification_settings SET rating_enabled=true WHERE user_id=10");
        assertThat(deliveries.enqueue(watch)).isZero();
    }

    @Test
    void workersClaimDistinctRowsAndRecoverExpiredLease() throws Exception {
        Instant now = Instant.now();
        long firstNotification =
                notification("PARTY_INVITE", 3, now.minusSeconds(1), now.plusSeconds(600));
        long secondNotification =
                notification("PARTY_INVITE", 30, now.minusSeconds(1), now.plusSeconds(600));
        deliveries.enqueue(firstNotification);
        deliveries.enqueue(secondNotification);
        UUID firstToken = UUID.randomUUID();
        UUID secondToken = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        Future<List<ClaimedDelivery>> firstFuture =
                executor.submit(
                        () -> {
                            start.await();
                            return deliveries.claimDue(now, Duration.ofMinutes(2), 1, firstToken);
                        });
        Future<List<ClaimedDelivery>> secondFuture =
                executor.submit(
                        () -> {
                            start.await();
                            return deliveries.claimDue(now, Duration.ofMinutes(2), 1, secondToken);
                        });
        start.countDown();
        ClaimedDelivery first = firstFuture.get().getFirst();
        ClaimedDelivery second = secondFuture.get().getFirst();
        executor.shutdownNow();

        assertThat(first.deliveryId()).isNotEqualTo(second.deliveryId());
        assertThat(first.attemptCount()).isOne();
        assertThat(second.attemptCount()).isOne();
        assertThat(deliveries.claimDue(now, Duration.ofMinutes(2), 10, UUID.randomUUID()))
                .isEmpty();

        Instant reclaimedAt = now.plus(Duration.ofMinutes(3));
        UUID reclaimToken = UUID.randomUUID();
        List<ClaimedDelivery> reclaimed =
                deliveries.claimDue(reclaimedAt, Duration.ofMinutes(2), 10, reclaimToken);
        assertThat(reclaimed).hasSize(2).allMatch(item -> item.attemptCount() == 2);
        ClaimedDelivery reclaimedFirst =
                reclaimed.stream()
                        .filter(item -> item.deliveryId() == first.deliveryId())
                        .findFirst()
                        .orElseThrow();
        assertThat(deliveries.markSent(first.deliveryId(), firstToken, reclaimedAt, 201)).isFalse();
        assertThat(deliveries.markSent(reclaimedFirst.deliveryId(), reclaimToken, reclaimedAt, 201))
                .isTrue();
    }

    @Test
    void cancelsReadAndNonPartyDeliveriesBeforeClaim() {
        Instant now = Instant.now();
        jdbc.update("UPDATE notification_settings SET rating_enabled=true WHERE user_id=10");
        long party = notification("PARTY_INVITE", 4, now.minusSeconds(1), now.plusSeconds(600));
        long watch = notification("WATCH_CONFIRM", 5, now.minusSeconds(1), now.plusSeconds(600));
        deliveries.enqueue(party);
        insertLegacyDelivery(watch, now.minusSeconds(1));
        jdbc.update("UPDATE notifications SET read_at=now() WHERE id=?", party);

        assertThat(deliveries.cancelIneligible(now)).isEqualTo(2);
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM web_push_deliveries ORDER BY id", String.class))
                .containsExactly("CANCELED", "CANCELED");
    }

    @Test
    void bulkReadCancellationDoesNotCancelFutureUnreadNotifications() {
        Instant now = Instant.now();
        jdbc.update("UPDATE notification_settings SET rating_enabled=true WHERE user_id=10");
        long visible = notification("PARTY_INVITE", 7, now.minusSeconds(1), now.plusSeconds(600));
        long future = notification("PARTY_INVITE", 8, now.plusSeconds(300), now.plusSeconds(900));
        deliveries.enqueue(visible);
        deliveries.enqueue(future);
        jdbc.update("UPDATE notifications SET read_at=? WHERE id=?", Timestamp.from(now), visible);

        assertThat(deliveries.cancelReadForUser(10, now)).isOne();
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM web_push_deliveries ORDER BY id", String.class))
                .containsExactly("CANCELED", "PENDING");
    }

    @Test
    void retriesOnlyWhenTheCurrentClaimTokenStillOwnsTheLease() {
        Instant now = Instant.now();
        long notification =
                notification("PARTY_INVITE", 6, now.minusSeconds(1), now.plusSeconds(600));
        deliveries.enqueue(notification);
        UUID token = UUID.randomUUID();
        List<ClaimedDelivery> claimed = deliveries.claimDue(now, Duration.ofMinutes(2), 10, token);

        assertThat(
                        deliveries.scheduleRetry(
                                claimed.getFirst().deliveryId(),
                                UUID.randomUUID(),
                                now.plusSeconds(60),
                                503,
                                "TRANSIENT"))
                .isFalse();
        assertThat(
                        deliveries.scheduleRetry(
                                claimed.getFirst().deliveryId(),
                                token,
                                now.plusSeconds(60),
                                503,
                                "TRANSIENT"))
                .isTrue();
        assertThat(deliveries.claimDue(now.plusSeconds(30), Duration.ofMinutes(2), 10, token))
                .isEmpty();
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

    private long insertLegacyDelivery(long notificationId, Instant nextAttemptAt) {
        return jdbc.queryForObject(
                "INSERT INTO web_push_deliveries(notification_id,subscription_id,"
                        + "endpoint_fingerprint,status,next_attempt_at)"
                        + " SELECT ?,id,endpoint_fingerprint,'PENDING',?"
                        + " FROM web_push_subscriptions WHERE id=? RETURNING id",
                Long.class,
                notificationId,
                Timestamp.from(nextAttemptAt),
                subscriptionId);
    }
}
