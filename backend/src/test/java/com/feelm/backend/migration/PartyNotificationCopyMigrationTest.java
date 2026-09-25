package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PartyNotificationCopyMigrationTest {

    private static final String PREVIOUS_VERSION = "202609251200";
    private static final String MIGRATION_VERSION = "202609251300";

    @Test
    void renamesOnlyLegacyPartyCopyAndPreservesNotificationState() throws SQLException {
        try (var database =
                MigrationPostgresSupport.databaseAtVersion(
                        "party_notification_copy", PREVIOUS_VERSION)) {
            execute(
                    database,
                    """
                    INSERT INTO users(id,email,nickname,birth_date,gender,status)
                    VALUES (99991,'party-copy@feelm.test','파티알림',DATE '2000-01-01','OTHER','ACTIVE');

                    INSERT INTO notifications(
                        user_id,type,source_id,target_type,target_id,title,body,
                        notified_at,expires_at,read_at,dismissed_at
                    ) VALUES
                        (99991,'PARTY_INVITE',991,'PARTY_INVITATION',991,
                         '파티 모드 초대','새로운 파티 초대가 도착했어요.',
                         now() - interval '1 day',now() + interval '89 days',now(),NULL),
                        (99991,'PARTY_INVITE_ACCEPTED',992,'PARTY',992,
                         '파티 모드 초대 수락','초대받은사람 님이 파티 모드에 참여했어요.',
                         now() - interval '1 day',now() + interval '89 days',NULL,now()),
                        (99991,'PARTY_INVITE_ACCEPTED',993,'PARTY',993,
                         '직접 작성한 제목','직접 작성한 본문',
                         now() - interval '1 day',now() + interval '89 days',NULL,NULL),
                        (99991,'WATCH_CONFIRM',994,'WATCH_RECORD',994,
                         '파티 모드 초대','새로운 파티 초대가 도착했어요.',
                         now() - interval '1 day',now() + interval '89 days',NULL,NULL),
                        (99991,'PARTY_INVITE',995,'PARTY_INVITATION',995,
                         '파티팝콘 초대','새로운 파티팝콘 초대가 도착했어요.',
                         now() - interval '1 day',now() + interval '89 days',NULL,NULL),
                        (99991,'PARTY_INVITE',996,'PARTY_INVITATION',996,
                         '직접 작성한 초대','새로운 파티 초대가 도착했어요.',
                         now() - interval '1 day',now() + interval '89 days',NULL,NULL),
                        (99991,'PARTY_INVITE_ACCEPTED',997,'PARTY',997,
                         '직접 작성한 수락','안내: 홍길동 님이 파티 모드에 참여했어요.',
                         now() - interval '1 day',now() + interval '89 days',NULL,NULL),
                        (99991,'PARTY_INVITE_ACCEPTED',998,'PARTY',998,
                         '파티 모드 초대 수락','직접 작성한 본문',
                         now() - interval '1 day',now() + interval '89 days',NULL,NULL);
                    """);
            String stateBefore =
                    query(
                            database,
                            """
                            SELECT string_agg(
                                id || ':' || type || ':' || source_id || ':' || target_type || ':'
                                || target_id || ':' || notified_at || ':' || expires_at || ':'
                                || coalesce(read_at::text,'') || ':'
                                || coalesce(dismissed_at::text,'') || ':' || updated_at,
                                '|' ORDER BY source_id)
                            FROM notifications WHERE user_id=99991
                            """);

            Flyway.configure()
                    .dataSource(
                            database.getJdbcUrl(), database.getUsername(), database.getPassword())
                    .target(MIGRATION_VERSION)
                    .load()
                    .migrate();

            assertThat(
                            query(
                                    database,
                                    """
                                    SELECT string_agg(source_id || ':' || title || ':' || body,
                                                      '|' ORDER BY source_id)
                                    FROM notifications WHERE user_id=99991
                                    """))
                    .isEqualTo(
                            "991:파티팝콘 초대:새로운 파티팝콘 초대가 도착했어요.|"
                                    + "992:파티팝콘 초대 수락:초대받은사람 님이 파티팝콘에 참여했어요.|"
                                    + "993:직접 작성한 제목:직접 작성한 본문|"
                                    + "994:파티 모드 초대:새로운 파티 초대가 도착했어요.|"
                                    + "995:파티팝콘 초대:새로운 파티팝콘 초대가 도착했어요.|"
                                    + "996:직접 작성한 초대:새로운 파티 초대가 도착했어요.|"
                                    + "997:직접 작성한 수락:안내: 홍길동 님이 파티 모드에 참여했어요.|"
                                    + "998:파티팝콘 초대 수락:직접 작성한 본문");
            assertThat(
                            query(
                                    database,
                                    """
                                    SELECT string_agg(
                                        id || ':' || type || ':' || source_id || ':' || target_type || ':'
                                        || target_id || ':' || notified_at || ':' || expires_at || ':'
                                        || coalesce(read_at::text,'') || ':'
                                        || coalesce(dismissed_at::text,'') || ':' || updated_at,
                                        '|' ORDER BY source_id)
                                    FROM notifications WHERE user_id=99991
                                    """))
                    .isEqualTo(stateBefore);
        }
    }

    private static void execute(MigrationPostgresSupport.MigrationTestDatabase database, String sql)
            throws SQLException {
        try (Connection connection = database.connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String query(MigrationPostgresSupport.MigrationTestDatabase database, String sql)
            throws SQLException {
        try (Connection connection = database.connect();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
