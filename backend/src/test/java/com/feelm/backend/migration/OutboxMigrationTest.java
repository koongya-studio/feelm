package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("Kafka 아웃박스 마이그레이션")
class OutboxMigrationTest {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(IMAGE)
                    .withDatabaseName("feelm")
                    .withUsername("feelm")
                    .withPassword("feelm")
                    .withEnv("POSTGRES_INITDB_ARGS", "--locale=C.UTF-8");

    @BeforeAll
    static void migrate() {
        flyway().migrate();
    }

    @Test
    @DisplayName("아웃박스 컬럼과 PENDING 조회용 부분 인덱스를 생성한다")
    void createsOutboxTableAndPendingIndex() {
        assertThat(columnsOf("outbox_events"))
                .containsExactlyInAnyOrder(
                        "id",
                        "event_id",
                        "event_type",
                        "topic",
                        "partition_key",
                        "message",
                        "status",
                        "attempt_count",
                        "next_attempt_at",
                        "last_attempt_at",
                        "last_error",
                        "created_at",
                        "published_at");
        assertThat(
                        values(
                                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public'"
                                        + " AND indexname = 'ix_outbox_events_pending_due'"))
                .singleElement()
                .asString()
                .contains("next_attempt_at", "id", "status", "PENDING");
    }

    @Test
    @DisplayName("공통 봉투와 바깥 식별 값의 일치를 강제한다")
    void enforcesEnvelopeConsistency() throws SQLException {
        String eventId = "0f3c9a1e-6b2d-4f77-9a10-5c2b8e41d0aa";
        insert(eventId, "RATING", 1024, message(eventId, "RATING", 1024));

        assertThatThrownBy(
                        () ->
                                insert(
                                        "1f3c9a1e-6b2d-4f77-9a10-5c2b8e41d0aa",
                                        "RATING",
                                        1024,
                                        message(eventId, "RATING", 1024)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_outbox_events_message_contract");
    }

    @Test
    @DisplayName("상태와 시도 횟수 제약을 적용한다")
    void enforcesStatusAndAttemptCount() {
        assertThat(constraintDefinition("ck_outbox_events_status"))
                .contains("PENDING", "PUBLISHED")
                .doesNotContain("FAILED");
        String eventId = "2f3c9a1e-6b2d-4f77-9a10-5c2b8e41d0aa";
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO outbox_events(event_id,event_type,topic,partition_key,message,status,attempt_count,next_attempt_at)"
                                                + " VALUES ('"
                                                + eventId
                                                + "','VIEW','feelm.view.v1',1,'"
                                                + message(eventId, "VIEW", 1)
                                                + "'::jsonb,'PENDING',-1,now())"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_outbox_events_attempt_count");
    }

    private static String constraintDefinition(String constraintName) {
        return values(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='"
                                + constraintName
                                + "'")
                .getFirst();
    }

    private static void insert(String eventId, String eventType, long userId, String message)
            throws SQLException {
        execute(
                "INSERT INTO outbox_events(event_id,event_type,topic,partition_key,message,status,attempt_count,next_attempt_at) VALUES ('"
                        + eventId
                        + "','"
                        + eventType
                        + "','feelm.rating.v1',"
                        + userId
                        + ",'"
                        + message
                        + "'::jsonb,'PENDING',0,now())");
    }

    private static String message(String eventId, String eventType, long userId) {
        return "{\"eventId\":\""
                + eventId
                + "\",\"eventType\":\""
                + eventType
                + "\",\"occurredAt\":\"2026-09-07T00:00:00Z\",\"userId\":"
                + userId
                + ",\"payload\":{}}";
    }

    private static List<String> columnsOf(String table) {
        return values(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='public'"
                        + " AND table_name='"
                        + table
                        + "'");
    }

    private static List<String> values(String sql) {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            List<String> result = new ArrayList<>();
            while (resultSet.next()) {
                result.add(resultSet.getString(1));
            }
            return result;
        } catch (SQLException exception) {
            throw new IllegalStateException(sql, exception);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load();
    }
}
