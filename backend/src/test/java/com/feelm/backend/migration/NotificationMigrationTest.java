package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("인앱 알림 schema migration")
class NotificationMigrationTest {

    private static final String BEFORE_NOTIFICATION_VERSION = "202609171000";
    private static final MigrationPostgresSupport.MigrationTestDatabase DATABASE =
            MigrationPostgresSupport.database("notification");

    private static long pendingInvitationId;
    private static long activeWatchId;

    @BeforeAll
    static void migrateLegacyFixtures() throws SQLException {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .target(BEFORE_NOTIFICATION_VERSION)
                .load()
                .migrate();

        long ownerId = insertUser("notification-owner@feelm.test", "알림오너", "ACTIVE");
        long inviteeId = insertUser("notification-invitee@feelm.test", "알림초대", "ACTIVE");
        long oldInviteeId = insertUser("notification-expired@feelm.test", "만료초대", "ACTIVE");
        long withdrawnId = insertUser("notification-old@feelm.test", "탈퇴알림", "WITHDRAWN");
        long partyId =
                insertId(
                        "INSERT INTO parties(name,owner_id,status) VALUES"
                                + " ('알림 파티',"
                                + ownerId
                                + ",'ACTIVE') RETURNING id");
        long closedPartyId =
                insertId(
                        "INSERT INTO parties(name,owner_id,status,closed_reason,closed_at) VALUES"
                                + " ('종료 파티',"
                                + ownerId
                                + ",'CLOSED','OWNER_CLOSED',now()) RETURNING id");

        pendingInvitationId =
                invitation(
                        partyId, ownerId, inviteeId, "PENDING", "now() - interval '1 day'", null);
        invitation(partyId, ownerId, inviteeId, "ACCEPTED", "now() - interval '2 days'", "now()");
        invitation(closedPartyId, ownerId, inviteeId, "PENDING", "now() - interval '1 day'", null);
        invitation(partyId, ownerId, withdrawnId, "PENDING", "now() - interval '1 day'", null);
        invitation(partyId, ownerId, oldInviteeId, "PENDING", "now() - interval '91 days'", null);

        long movieId =
                insertId(
                        "INSERT INTO movies(tmdb_id,title) VALUES"
                                + " (1910001,'알림 마이그레이션 영화') RETURNING id");
        activeWatchId =
                watch(
                        inviteeId,
                        movieId,
                        "LINK_CLICKED",
                        "now() - interval '1 day'",
                        "now() + interval '1 day'",
                        "now() + interval '6 days'");
        watch(
                inviteeId,
                movieId,
                "EXPIRED",
                "now() - interval '10 days'",
                "now() - interval '8 days'",
                "now() - interval '3 days'");
        watch(
                withdrawnId,
                movieId,
                "LINK_CLICKED",
                "now() - interval '1 day'",
                "now() + interval '1 day'",
                "now() + interval '6 days'");

        latestFlyway().migrate();
    }

    @AfterAll
    static void dropDatabase() {
        DATABASE.close();
    }

    @Test
    @DisplayName("기존 활성 파티 초대와 감상 확인만 알림 정본으로 이관한다")
    void backfillsOnlyActiveNotificationSources() throws SQLException {
        assertThat(longValue("SELECT count(*) FROM notifications")).isEqualTo(2);
        assertThat(
                        longValue(
                                "SELECT count(*) FROM notifications WHERE type='PARTY_INVITE'"
                                        + " AND source_id="
                                        + pendingInvitationId
                                        + " AND target_type='PARTY_INVITATION'"
                                        + " AND notified_at < expires_at"))
                .isOne();
        assertThat(
                        longValue(
                                "SELECT count(*) FROM notifications WHERE type='WATCH_CONFIRM'"
                                        + " AND source_id="
                                        + activeWatchId
                                        + " AND target_type='WATCH_RECORD'"
                                        + " AND notified_at < expires_at"))
                .isOne();
    }

    @Test
    @DisplayName("알림 마이그레이션은 반복 실행해도 중복을 만들지 않는다")
    void migrationIsStableOnRepeatedFlywayMigrate() throws SQLException {
        assertThat(latestFlyway().migrate().migrationsExecuted).isZero();
        assertThat(longValue("SELECT count(*) FROM notifications")).isEqualTo(2);
    }

    @Test
    @DisplayName("사용자 숨김 시각과 미확인 조회 인덱스를 추가한다")
    void addsDismissedAtAndUnreadIndexFilter() throws SQLException {
        assertThat(
                        longValue(
                                "SELECT count(*) FROM information_schema.columns"
                                        + " WHERE table_schema='public'"
                                        + " AND table_name='notifications'"
                                        + " AND column_name='dismissed_at'"
                                        + " AND data_type='timestamp with time zone'"))
                .isOne();
        assertThat(
                        longValue(
                                "SELECT count(*) FROM pg_indexes"
                                        + " WHERE schemaname='public'"
                                        + " AND tablename='notifications'"
                                        + " AND indexname='ix_notifications_user_unread'"
                                        + " AND indexdef LIKE '%dismissed_at IS NULL%'"))
                .isOne();
    }

    private static Flyway latestFlyway() {
        return Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .load();
    }

    private static long insertUser(String email, String nickname, String status)
            throws SQLException {
        String withdrawnAt = status.equals("WITHDRAWN") ? "now()" : "NULL";
        return insertId(
                "INSERT INTO users(email,nickname,birth_date,gender,status,withdrawn_at) VALUES ('"
                        + email
                        + "','"
                        + nickname
                        + "',DATE '1995-05-05','OTHER','"
                        + status
                        + "',"
                        + withdrawnAt
                        + ") RETURNING id");
    }

    private static long invitation(
            long partyId,
            long inviterId,
            long inviteeId,
            String status,
            String createdAt,
            String respondedAt)
            throws SQLException {
        return insertId(
                "INSERT INTO party_invitations(party_id,inviter_id,invitee_id,status,created_at,"
                        + "responded_at) VALUES ("
                        + partyId
                        + ","
                        + inviterId
                        + ","
                        + inviteeId
                        + ",'"
                        + status
                        + "',"
                        + createdAt
                        + ","
                        + (respondedAt == null ? "NULL" : respondedAt)
                        + ") RETURNING id");
    }

    private static long watch(
            long userId,
            long movieId,
            String status,
            String clickedAt,
            String confirmDueAt,
            String expiresAt)
            throws SQLException {
        return insertId(
                "INSERT INTO watch_records(user_id,movie_id,status,clicked_at,confirm_due_at,"
                        + "expires_at) VALUES ("
                        + userId
                        + ","
                        + movieId
                        + ",'"
                        + status
                        + "',"
                        + clickedAt
                        + ","
                        + confirmDueAt
                        + ","
                        + expiresAt
                        + ") RETURNING id");
    }

    private static long insertId(String sql) throws SQLException {
        return longValue(sql);
    }

    private static long longValue(String sql) throws SQLException {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
