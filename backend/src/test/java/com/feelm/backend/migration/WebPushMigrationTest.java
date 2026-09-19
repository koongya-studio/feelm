package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Web Push schema migration")
class WebPushMigrationTest {

    private static final String PARTY_ONLY_PUSH_PREVIOUS_VERSION = "202609181300";
    private static final MigrationPostgresSupport.MigrationTestDatabase DATABASE =
            MigrationPostgresSupport.database("web_push");
    private static long userId;
    private static long notificationId;

    @BeforeAll
    static void migrate() throws SQLException {
        DATABASE.flyway().migrate();
        userId =
                longValue(
                        "INSERT INTO users(email,nickname,birth_date,gender,status) VALUES"
                                + " ('push-migration@feelm.test','푸시이관',DATE '2000-01-01',"
                                + "'OTHER','ACTIVE') RETURNING id");
        notificationId =
                longValue(
                        "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,"
                                + "title,body,notified_at,expires_at) VALUES ("
                                + userId
                                + ",'PARTY_INVITE',991,'PARTY_INVITATION',991,'제목','본문',"
                                + "now(),now()+interval '1 day') RETURNING id");
    }

    @AfterAll
    static void dropDatabase() {
        DATABASE.close();
    }

    @Test
    void createsConstrainedSubscriptionAndDeliveryTables() throws SQLException {
        long subscriptionId = subscription("a".repeat(64));
        insertDelivery(subscriptionId, "a".repeat(64));

        assertThatThrownBy(() -> subscription("a".repeat(64))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertDelivery(subscriptionId, "a".repeat(64)))
                .isInstanceOf(SQLException.class);
        assertThat(
                        longValue(
                                "SELECT count(*) FROM pg_indexes WHERE schemaname='public'"
                                        + " AND indexname IN ('ix_web_push_subscriptions_user_active',"
                                        + "'ix_web_push_deliveries_due',"
                                        + "'ix_web_push_deliveries_subscription_pending')"))
                .isEqualTo(3);
        assertThat(
                        longValue(
                                "SELECT count(*) FROM information_schema.table_constraints"
                                        + " WHERE table_schema='public' AND table_name IN"
                                        + " ('web_push_subscriptions','web_push_deliveries')"
                                        + " AND constraint_type IN ('FOREIGN KEY','CHECK','UNIQUE')"))
                .isGreaterThanOrEqualTo(8);
    }

    @Test
    void cascadesUserAndNotificationWithoutLeakingSubscriptionSecrets() throws SQLException {
        long cascadeUser =
                longValue(
                        "INSERT INTO users(email,nickname,birth_date,gender,status) VALUES"
                                + " ('push-cascade@feelm.test','푸시삭제',DATE '2000-01-01',"
                                + "'OTHER','ACTIVE') RETURNING id");
        long cascadeNotification =
                longValue(
                        "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,"
                                + "title,body,notified_at,expires_at) VALUES ("
                                + cascadeUser
                                + ",'PARTY_INVITE',992,'PARTY_INVITATION',992,'제목','본문',"
                                + "now(),now()+interval '1 day') RETURNING id");
        long cascadeSubscription =
                longValue(
                        "INSERT INTO web_push_subscriptions(user_id,endpoint,endpoint_fingerprint,"
                                + "p256dh_key,auth_secret,capability_version) VALUES ("
                                + cascadeUser
                                + ",'https://fcm.googleapis.com/fcm/send/cascade','"
                                + "9".repeat(64)
                                + "','secret-key','secret-auth',1) RETURNING id");
        execute(
                "INSERT INTO web_push_deliveries(notification_id,subscription_id,"
                        + "endpoint_fingerprint,status,next_attempt_at) VALUES ("
                        + cascadeNotification
                        + ","
                        + cascadeSubscription
                        + ",'"
                        + "9".repeat(64)
                        + "','PENDING',now())");

        execute("DELETE FROM notifications WHERE id=" + cascadeNotification);
        assertThat(
                        longValue(
                                "SELECT count(*) FROM web_push_deliveries WHERE notification_id="
                                        + cascadeNotification))
                .isZero();

        execute("DELETE FROM users WHERE id=" + cascadeUser);
        assertThat(
                        longValue(
                                "SELECT count(*) FROM web_push_subscriptions WHERE id="
                                        + cascadeSubscription))
                .isZero();
    }

    @Test
    void activeSubscriptionRequiresCapabilityAndProcessingDeliveryRequiresLeaseToken()
            throws SQLException {
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO web_push_subscriptions(user_id,"
                                                + "endpoint_fingerprint,active,capability_version) VALUES ("
                                                + userId
                                                + ",'"
                                                + "b".repeat(64)
                                                + "',true,1)"))
                .isInstanceOf(SQLException.class);
        long subscriptionId = subscription("c".repeat(64));
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO web_push_subscriptions(user_id,endpoint,"
                                                + "endpoint_fingerprint,p256dh_key,auth_secret,"
                                                + "capability_version) VALUES ("
                                                + userId
                                                + ",'https://fcm.googleapis.com/fcm/send/invalid-version','"
                                                + "d".repeat(64)
                                                + "','key','auth',0)"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO web_push_deliveries(notification_id,"
                                                + "subscription_id,endpoint_fingerprint,"
                                                + "next_attempt_at) VALUES ("
                                                + notificationId
                                                + ","
                                                + subscriptionId
                                                + ",'"
                                                + "e".repeat(64)
                                                + "',now())"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO web_push_deliveries(notification_id,"
                                                + "subscription_id,endpoint_fingerprint,status,"
                                                + "next_attempt_at,claimed_at) VALUES ("
                                                + notificationId
                                                + ","
                                                + subscriptionId
                                                + ",'"
                                                + "c".repeat(64)
                                                + "','PROCESSING',now(),now())"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void acceptsPartyInvitationAcceptanceTargetAndRejectsMismatches() throws SQLException {
        long acceptedNotification =
                longValue(
                        "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,"
                                + "title,body,notified_at,expires_at) VALUES ("
                                + userId
                                + ",'PARTY_INVITE_ACCEPTED',993,'PARTY',77,'제목','본문',"
                                + "now(),now()+interval '1 day') RETURNING id");

        assertThat(acceptedNotification).isPositive();
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO notifications(user_id,type,source_id,"
                                                + "target_type,target_id,title,body,notified_at,"
                                                + "expires_at) VALUES ("
                                                + userId
                                                + ",'PARTY_INVITE_ACCEPTED',994,'WATCH_RECORD',77,"
                                                + "'제목','본문',now(),now()+interval '1 day')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void reschedulesPendingWatchConfirmationAndCancelsItsPushDelivery() throws SQLException {
        try (MigrationPostgresSupport.MigrationTestDatabase upgradeDatabase =
                MigrationPostgresSupport.database("party_only_push")) {
            Flyway.configure()
                    .dataSource(
                            upgradeDatabase.getJdbcUrl(),
                            upgradeDatabase.getUsername(),
                            upgradeDatabase.getPassword())
                    .target(PARTY_ONLY_PUSH_PREVIOUS_VERSION)
                    .load()
                    .migrate();
            long upgradeUser =
                    longValue(
                            upgradeDatabase,
                            "INSERT INTO users(email,nickname,birth_date,gender,status) VALUES"
                                    + " ('party-only-push@feelm.test','푸시정책변경',"
                                    + "DATE '2000-01-01','OTHER','ACTIVE') RETURNING id");
            long movieId =
                    longValue(
                            upgradeDatabase,
                            "INSERT INTO movies(tmdb_id,title) VALUES"
                                    + " (991991,'24시간 감상 확인') RETURNING id");
            long watchId =
                    longValue(
                            upgradeDatabase,
                            "INSERT INTO watch_records(user_id,movie_id,status,clicked_at,"
                                    + "confirm_due_at,expires_at) VALUES ("
                                    + upgradeUser
                                    + ","
                                    + movieId
                                    + ",'LINK_CLICKED',TIMESTAMPTZ '2026-09-10 00:00:00+00',"
                                    + "TIMESTAMPTZ '2026-09-12 00:00:00+00',"
                                    + "TIMESTAMPTZ '2026-09-17 00:00:00+00') RETURNING id");
            long watchNotification =
                    longValue(
                            upgradeDatabase,
                            "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,"
                                    + "title,body,notified_at,expires_at) VALUES ("
                                    + upgradeUser
                                    + ",'WATCH_CONFIRM',"
                                    + watchId
                                    + ",'WATCH_RECORD',"
                                    + watchId
                                    + ",'제목','본문',TIMESTAMPTZ '2026-09-12 00:00:00+00',"
                                    + "TIMESTAMPTZ '2026-09-17 00:00:00+00') RETURNING id");
            long upgradeSubscription =
                    longValue(
                            upgradeDatabase,
                            "INSERT INTO web_push_subscriptions(user_id,endpoint,"
                                    + "endpoint_fingerprint,p256dh_key,auth_secret,capability_version)"
                                    + " VALUES ("
                                    + upgradeUser
                                    + ",'https://fcm.googleapis.com/fcm/send/party-only','"
                                    + "f".repeat(64)
                                    + "','key','auth',1) RETURNING id");
            execute(
                    upgradeDatabase,
                    "INSERT INTO web_push_deliveries(notification_id,subscription_id,"
                            + "endpoint_fingerprint,status,next_attempt_at) VALUES ("
                            + watchNotification
                            + ","
                            + upgradeSubscription
                            + ",'"
                            + "f".repeat(64)
                            + "','PENDING',TIMESTAMPTZ '2026-09-12 00:00:00+00')");

            // 이 클래스 뒤에 다른 마이그레이션이 붙으면 실행 수가 늘어난다. 이 테스트가 보는 것은
            // 기존 데이터를 지키며 업그레이드되는지이지 실행 수가 아니다(S15P21E106-604).
            assertThat(upgradeDatabase.flyway().migrate().migrationsExecuted).isPositive();
            assertThat(
                            longValue(
                                    upgradeDatabase,
                                    "SELECT extract(epoch FROM (confirm_due_at-clicked_at))::bigint"
                                            + " FROM watch_records WHERE id="
                                            + watchId))
                    .isEqualTo(86_400);
            assertThat(
                            longValue(
                                    upgradeDatabase,
                                    "SELECT extract(epoch FROM (notification.notified_at-"
                                            + "watch.clicked_at))::bigint FROM notifications notification"
                                            + " JOIN watch_records watch ON watch.id=notification.source_id"
                                            + " WHERE notification.id="
                                            + watchNotification))
                    .isEqualTo(86_400);
            assertThat(
                            stringValue(
                                    upgradeDatabase,
                                    "SELECT status || ':' || failure_code FROM web_push_deliveries"
                                            + " WHERE notification_id="
                                            + watchNotification))
                    .isEqualTo("CANCELED:NOT_PUSH_ELIGIBLE");
        }
    }

    private static long subscription(String fingerprint) throws SQLException {
        return longValue(
                "INSERT INTO web_push_subscriptions(user_id,endpoint,endpoint_fingerprint,"
                        + "p256dh_key,auth_secret,capability_version) VALUES ("
                        + userId
                        + ",'https://fcm.googleapis.com/fcm/send/"
                        + fingerprint.charAt(0)
                        + "','"
                        + fingerprint
                        + "','key','auth',1) RETURNING id");
    }

    private static void insertDelivery(long subscriptionId, String fingerprint)
            throws SQLException {
        execute(
                "INSERT INTO web_push_deliveries(notification_id,subscription_id,"
                        + "endpoint_fingerprint,status,next_attempt_at) VALUES ("
                        + notificationId
                        + ","
                        + subscriptionId
                        + ",'"
                        + fingerprint
                        + "','PENDING',now())");
    }

    private static void execute(String sql) throws SQLException {
        execute(DATABASE, sql);
    }

    private static void execute(MigrationPostgresSupport.MigrationTestDatabase database, String sql)
            throws SQLException {
        try (Connection connection = database.connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long longValue(String sql) throws SQLException {
        return longValue(DATABASE, sql);
    }

    private static long longValue(
            MigrationPostgresSupport.MigrationTestDatabase database, String sql)
            throws SQLException {
        try (Connection connection = database.connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static String stringValue(
            MigrationPostgresSupport.MigrationTestDatabase database, String sql)
            throws SQLException {
        try (Connection connection = database.connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }
}
