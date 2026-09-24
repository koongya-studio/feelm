package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 파티 요청과 전용 Kafka JSON을 실제 PostgreSQL 제약으로 검증한다. */
class PartyRefreshMigrationTest {
    private static final MigrationPostgresSupport.MigrationTestDatabase DATABASE =
            MigrationPostgresSupport.database("party_refresh");

    @BeforeAll
    static void migrate() throws SQLException {
        DATABASE.flyway().migrate();
        execute(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (1024,'refresh@example.test','refresh',DATE '2000-01-01',"
                        + "'OTHER','ACTIVE')");
        execute("INSERT INTO parties(id,name,owner_id,status) VALUES (9001,'party',1024,'ACTIVE')");
    }

    @AfterAll
    static void close() {
        DATABASE.close();
    }

    @Test
    void acceptsPartyRefreshBodyAndKeepsOldEnvelopeConstraint() throws SQLException {
        execute(
                """
                INSERT INTO outbox_events(event_id,event_type,topic,partition_key,message,
                                          status,attempt_count,next_attempt_at)
                VALUES ('00000000-0000-0000-0000-000000000544','PARTY_REFRESH',
                        'feelm.party-refresh.v1',9001,
                        '{"partyId":9001,"inputId":"sha256:abcd","members":[{"userId":1024}]}',
                        'PENDING',0,now())
                """);

        assertThatThrownBy(
                        () ->
                                execute(
                                        """
                                        INSERT INTO outbox_events(event_id,event_type,topic,
                                                                  partition_key,message,status,
                                                                  attempt_count,next_attempt_at)
                                        VALUES ('00000000-0000-0000-0000-000000000545',
                                                'PARTY_REFRESH','feelm.party-refresh.v1',9002,
                                                '{"partyId":9001,"inputId":"sha256:abcd","members":[{"userId":1024}]}',
                                                'PENDING',0,now())
                                        """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_outbox_events_message_contract");

        assertThatThrownBy(
                        () ->
                                execute(
                                        """
                                        INSERT INTO outbox_events(event_id,event_type,topic,
                                                                  partition_key,message,status,
                                                                  attempt_count,next_attempt_at)
                                        VALUES ('00000000-0000-0000-0000-000000000546',
                                                'RATING','feelm.rating.v1',1024,'{}',
                                                'PENDING',0,now())
                                        """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_outbox_events_message_contract");
    }

    @Test
    void rejectsUnexpectedPartyRefreshTopLevelFields() {
        String[] unexpectedFields = {
            "\"schemaVersion\":2",
            "\"modelVersion\":\"gbt-v26\"",
            "\"servingRevision\":\"serving-r1\""
        };
        for (int index = 0; index < unexpectedFields.length; index++) {
            String eventId = "00000000-0000-0000-0000-%012d".formatted(600 + index);
            String message =
                    "{\"partyId\":9001,\"inputId\":\"sha256:abcd\",\"members\":[{\"userId\":1024}],"
                            + unexpectedFields[index]
                            + "}";

            assertThatThrownBy(
                            () ->
                                    execute(
                                            """
                                            INSERT INTO outbox_events(
                                                event_id,event_type,topic,partition_key,message,
                                                status,attempt_count,next_attempt_at
                                            ) VALUES ('%s','PARTY_REFRESH','feelm.party-refresh.v1',
                                                      9001,'%s','PENDING',0,now())
                                            """
                                                    .formatted(eventId, message)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_outbox_events_message_contract");
        }
    }

    @Test
    void keepsOnePendingRequestPerParty() throws SQLException {
        execute("INSERT INTO party_refresh_requests(party_id) VALUES (9001)");
        execute(
                """
                INSERT INTO party_refresh_requests(party_id) VALUES (9001)
                ON CONFLICT (party_id) DO UPDATE SET
                    request_version = party_refresh_requests.request_version + 1
                """);

        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "SELECT count(*), max(request_version)"
                                        + " FROM party_refresh_requests WHERE party_id = 9001")) {
            result.next();
            assertThat(result.getLong(1)).isEqualTo(1);
            assertThat(result.getLong(2)).isEqualTo(2);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
