package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 파티 후보 게시 상태의 FK, 식별자와 모델 버전 DB 경계를 검증한다. */
class PartyCandidatePublicationMigrationTest {
    private static final MigrationPostgresSupport.MigrationTestDatabase DATABASE =
            MigrationPostgresSupport.database("party_candidate_publication");

    @BeforeAll
    static void migrate() throws SQLException {
        DATABASE.flyway().migrate();
    }

    @BeforeEach
    void fixtures() throws SQLException {
        execute("DELETE FROM parties");
        execute("DELETE FROM users");
        execute(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (1024,'publication@example.test','publication',"
                        + " DATE '2000-01-01','OTHER','ACTIVE')");
        execute("INSERT INTO parties(id,name,owner_id,status) VALUES (9001,'party',1024,'ACTIVE')");
    }

    @AfterAll
    static void close() {
        DATABASE.close();
    }

    @Test
    void acceptsFiftyCharacterModelAndCascadesWithParty() throws SQLException {
        execute(
                "INSERT INTO party_candidate_publications"
                        + " (party_id,input_id,model_version,generated_at,source_partition,source_offset)"
                        + " VALUES (9001,'"
                        + inputId()
                        + "','"
                        + "x".repeat(50)
                        + "',now(),0,1)");
        assertThat(count()).isEqualTo(1);

        execute("DELETE FROM parties WHERE id=9001");

        assertThat(count()).isZero();
    }

    @Test
    void rejectsModelOutsideArtifactContractAndInvalidInputId() {
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO party_candidate_publications"
                                                + " (party_id,input_id,model_version,generated_at,"
                                                + "source_partition,source_offset) VALUES (9001,'"
                                                + inputId()
                                                + "','"
                                                + "x".repeat(51)
                                                + "',now(),0,1)"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO party_candidate_publications"
                                                + " (party_id,input_id,model_version,generated_at,"
                                                + "source_partition,source_offset) VALUES (9001,'"
                                                + inputId()
                                                + "','ALS-V1',now(),0,1)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_party_candidate_publications_model_version");
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO party_candidate_publications"
                                                + " (party_id,input_id,model_version,generated_at,"
                                                + "source_partition,source_offset)"
                                                + " VALUES (9001,'sha256:bad','als-v1',now(),0,1)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_party_candidate_publications_input_id");
    }

    private static long count() throws SQLException {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "SELECT count(*) FROM party_candidate_publications")) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String inputId() {
        return "sha256:" + "a".repeat(64);
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
