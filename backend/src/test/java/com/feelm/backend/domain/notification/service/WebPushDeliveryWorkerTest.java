package com.feelm.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.notification.config.WebPushProperties;
import com.feelm.backend.domain.notification.config.WebPushProperties.Mode;
import com.feelm.backend.domain.notification.push.WebPushGateway;
import com.feelm.backend.domain.notification.push.WebPushMessage;
import com.feelm.backend.domain.notification.push.WebPushSendResult;
import com.feelm.backend.domain.notification.repository.WebPushDeliveryRepository;
import com.feelm.backend.domain.notification.repository.WebPushSubscriptionRepository;
import com.feelm.backend.support.SharedPostgresContractTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
class WebPushDeliveryWorkerTest extends SharedPostgresContractTest {

    private static final Instant BASE_TIME = Instant.parse("2026-09-18T00:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired WebPushDeliveryRepository deliveries;
    @Autowired WebPushSubscriptionRepository subscriptions;
    @Autowired WebPushPayloadFactory payloadFactory;

    private final MutableClock clock = new MutableClock(BASE_TIME);
    private final RecordingGateway gateway = new RecordingGateway();
    private WebPushDeliveryWorker worker;
    private long subscriptionId;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (10,'worker@example.test','worker',DATE '2000-01-01',"
                        + "'OTHER','ACTIVE')");
        jdbc.update(
                "INSERT INTO notification_settings(user_id,rating_enabled,recommendation_enabled,"
                        + "marketing_enabled) VALUES (10,true,true,false)");
        subscriptionId =
                subscriptions.upsert(
                        10,
                        "https://fcm.googleapis.com/fcm/send/worker",
                        "e".repeat(64),
                        "p256dh",
                        "auth",
                        null);
        worker =
                new WebPushDeliveryWorker(
                        deliveries,
                        subscriptions,
                        gateway,
                        properties(),
                        payloadFactory,
                        new SimpleMeterRegistry(),
                        clock);
        gateway.result = WebPushSendResult.sent(201);
        gateway.calls.set(0);
        gateway.beforeResult = () -> {};
        clock.set(BASE_TIME);
    }

    @Test
    void successMarksSentOnce() {
        long deliveryId = enqueue("PARTY_INVITE", 1, BASE_TIME.plusSeconds(600));

        worker.dispatch();
        worker.dispatch();

        assertThat(gateway.calls).hasValue(1);
        assertThat(row(deliveryId))
                .containsEntry("status", "SENT")
                .containsEntry("attempt_count", 1);
        assertThat(row(deliveryId).get("delivered_at")).isNotNull();
    }

    @Test
    void transientFailureSchedulesBoundedBackoff() {
        long deliveryId = enqueue("PARTY_INVITE", 2, BASE_TIME.plusSeconds(30));
        gateway.result = WebPushSendResult.retryable(503, "PUSH_SERVICE_TRANSIENT");

        worker.dispatch();

        assertThat(row(deliveryId))
                .containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 1)
                .containsEntry("last_http_status", 503);
        assertThat(((Timestamp) row(deliveryId).get("next_attempt_at")).toInstant())
                .isEqualTo(BASE_TIME.plusSeconds(30));
    }

    @Test
    void exhaustedRetryRecordsFinalFailure() {
        long deliveryId = enqueue("PARTY_INVITE", 3, BASE_TIME.plusSeconds(600));
        jdbc.update("UPDATE web_push_deliveries SET attempt_count=4 WHERE id=?", deliveryId);
        gateway.result = WebPushSendResult.retryable(429, "PUSH_SERVICE_TRANSIENT");

        worker.dispatch();

        assertThat(row(deliveryId))
                .containsEntry("status", "FAILED")
                .containsEntry("attempt_count", 5)
                .containsEntry("failure_code", "RETRY_EXHAUSTED");
    }

    @Test
    void goneSubscriptionIsPurgedAndOtherDeliveriesCanceled() {
        long failed = enqueue("PARTY_INVITE", 4, BASE_TIME.plusSeconds(600));
        long sibling = enqueue("PARTY_INVITE", 5, BASE_TIME.plusSeconds(600));
        gateway.result = WebPushSendResult.expired(410);

        worker.dispatch();

        assertThat(gateway.calls).hasValue(1);
        assertThat(row(failed).get("status")).isEqualTo("FAILED");
        assertThat(row(sibling).get("status")).isEqualTo("CANCELED");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT active,endpoint,p256dh_key,auth_secret"
                                        + " FROM web_push_subscriptions WHERE id=?",
                                subscriptionId))
                .containsEntry("active", false)
                .containsEntry("endpoint", null)
                .containsEntry("p256dh_key", null)
                .containsEntry("auth_secret", null);
    }

    @Test
    void expiredResponseCannotDeactivateCapabilityRefreshedDuringSend() {
        long deliveryId = enqueue("PARTY_INVITE", 40, BASE_TIME.plusSeconds(600));
        gateway.result = WebPushSendResult.expired(410);
        gateway.beforeResult =
                () ->
                        subscriptions.upsert(
                                10,
                                "https://fcm.googleapis.com/fcm/send/worker",
                                "e".repeat(64),
                                "refreshed-p256dh",
                                "refreshed-auth",
                                null);

        worker.dispatch();

        assertThat(row(deliveryId).get("status")).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT active,p256dh_key,auth_secret,capability_version"
                                        + " FROM web_push_subscriptions WHERE id=?",
                                subscriptionId))
                .containsEntry("active", true)
                .containsEntry("p256dh_key", "refreshed-p256dh")
                .containsEntry("auth_secret", "refreshed-auth")
                .containsEntry("capability_version", 2L);
    }

    @Test
    void claimsEachDeliveryImmediatelyBeforeItsSend() {
        enqueue("PARTY_INVITE", 41, BASE_TIME.plusSeconds(600));
        enqueue("PARTY_INVITE", 42, BASE_TIME.plusSeconds(600));
        AtomicInteger maximumProcessing = new AtomicInteger();
        gateway.beforeResult =
                () ->
                        maximumProcessing.accumulateAndGet(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM web_push_deliveries"
                                                + " WHERE status='PROCESSING'",
                                        Integer.class),
                                Math::max);

        worker.dispatch();

        assertThat(gateway.calls).hasValue(2);
        assertThat(maximumProcessing).hasValue(1);
    }

    @Test
    void skipsNoLongerEligibleOrNonPartyNotification() {
        long read = enqueue("PARTY_INVITE", 6, BASE_TIME.plusSeconds(600));
        long watch = insertLegacyDelivery("WATCH_CONFIRM", 7, BASE_TIME.plusSeconds(600));
        long dismissed = enqueue("PARTY_INVITE", 8, BASE_TIME.plusSeconds(600));
        long closed = enqueue("PARTY_INVITE", 9, BASE_TIME.plusSeconds(600));
        long expired = enqueue("PARTY_INVITE", 10, BASE_TIME.plusSeconds(600));
        jdbc.update(
                "UPDATE notifications SET read_at=? WHERE id=(SELECT notification_id"
                        + " FROM web_push_deliveries WHERE id=?)",
                Timestamp.from(BASE_TIME),
                read);
        jdbc.update(
                "UPDATE notifications SET dismissed_at=? WHERE id=(SELECT notification_id"
                        + " FROM web_push_deliveries WHERE id=?)",
                Timestamp.from(BASE_TIME),
                dismissed);
        jdbc.update(
                "UPDATE notifications SET closed_at=? WHERE id=(SELECT notification_id"
                        + " FROM web_push_deliveries WHERE id=?)",
                Timestamp.from(BASE_TIME),
                closed);
        jdbc.update(
                "UPDATE notifications SET expires_at=? WHERE id=(SELECT notification_id"
                        + " FROM web_push_deliveries WHERE id=?)",
                Timestamp.from(BASE_TIME),
                expired);
        worker.dispatch();

        assertThat(gateway.calls).hasValue(0);
        assertThat(row(read).get("status")).isEqualTo("CANCELED");
        assertThat(row(watch).get("status")).isEqualTo("CANCELED");
        assertThat(row(dismissed).get("status")).isEqualTo("CANCELED");
        assertThat(row(closed).get("status")).isEqualTo("CANCELED");
        assertThat(row(expired).get("status")).isEqualTo("CANCELED");
    }

    private long enqueue(String type, long sourceId, Instant expiresAt) {
        String targetType = type.equals("PARTY_INVITE") ? "PARTY_INVITATION" : "WATCH_RECORD";
        long notificationId =
                jdbc.queryForObject(
                        "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,"
                                + "title,body,notified_at,expires_at) VALUES"
                                + " (10,?,?,?,?, '제목','본문',?,?) RETURNING id",
                        Long.class,
                        type,
                        sourceId,
                        targetType,
                        sourceId,
                        Timestamp.from(BASE_TIME.minusSeconds(1)),
                        Timestamp.from(expiresAt));
        deliveries.enqueue(notificationId);
        return jdbc.queryForObject(
                "SELECT id FROM web_push_deliveries WHERE notification_id=?",
                Long.class,
                notificationId);
    }

    private long insertLegacyDelivery(String type, long sourceId, Instant expiresAt) {
        String targetType = type.equals("PARTY_INVITE") ? "PARTY_INVITATION" : "WATCH_RECORD";
        long notificationId =
                jdbc.queryForObject(
                        "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,"
                                + "title,body,notified_at,expires_at) VALUES"
                                + " (10,?,?,?,?, '제목','본문',?,?) RETURNING id",
                        Long.class,
                        type,
                        sourceId,
                        targetType,
                        sourceId,
                        Timestamp.from(BASE_TIME.minusSeconds(1)),
                        Timestamp.from(expiresAt));
        return jdbc.queryForObject(
                "INSERT INTO web_push_deliveries(notification_id,subscription_id,"
                        + "endpoint_fingerprint,status,next_attempt_at)"
                        + " SELECT ?,id,endpoint_fingerprint,'PENDING',?"
                        + " FROM web_push_subscriptions WHERE id=? RETURNING id",
                Long.class,
                notificationId,
                Timestamp.from(BASE_TIME.minusSeconds(1)),
                subscriptionId);
    }

    private java.util.Map<String, Object> row(long deliveryId) {
        return jdbc.queryForMap("SELECT * FROM web_push_deliveries WHERE id=?", deliveryId);
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

    private static final class RecordingGateway implements WebPushGateway {
        private final AtomicInteger calls = new AtomicInteger();
        private WebPushSendResult result;
        private Runnable beforeResult;

        @Override
        public WebPushSendResult send(WebPushMessage message) {
            calls.incrementAndGet();
            beforeResult.run();
            return result;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
