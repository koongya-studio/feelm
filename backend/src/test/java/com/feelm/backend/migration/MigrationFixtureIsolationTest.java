package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class MigrationFixtureIsolationTest {

    private static final String VERSION = "202609140120";

    @Test
    void clonedFixtureRetainsHistoryAndDoesNotShareRowsOrSequences() throws Exception {
        try (var first = MigrationPostgresSupport.databaseAtVersion("fixture_first", VERSION);
                var second =
                        MigrationPostgresSupport.databaseAtVersion("fixture_second", VERSION)) {
            assertThat(first.getJdbcUrl()).isNotEqualTo(second.getJdbcUrl());
            assertThat(query(first, "SELECT max(version) FROM flyway_schema_history"))
                    .isEqualTo(VERSION);
            assertThat(query(second, "SELECT max(version) FROM flyway_schema_history"))
                    .isEqualTo(VERSION);
            String insert =
                    "INSERT INTO otts(code,name,tmdb_provider_id)"
                            + " VALUES ('FIXTURE','Fixture',99999) RETURNING id";
            String firstId = query(first, insert);
            assertThat(query(second, "SELECT count(*) FROM otts WHERE code='FIXTURE'"))
                    .isEqualTo("0");
            assertThat(query(second, insert)).isEqualTo(firstId);
            first.flyway().migrate();
            assertThat(query(second, "SELECT max(version) FROM flyway_schema_history"))
                    .isEqualTo(VERSION);
        }
        try (var fresh = MigrationPostgresSupport.databaseAtVersion("fixture_fresh", VERSION)) {
            assertThat(query(fresh, "SELECT count(*) FROM otts WHERE code='FIXTURE'"))
                    .isEqualTo("0");
            assertThat(query(fresh, "SELECT max(version) FROM flyway_schema_history"))
                    .isEqualTo(VERSION);
        }
    }

    private static String query(MigrationPostgresSupport.MigrationTestDatabase database, String sql)
            throws SQLException {
        try (var connection = database.connect();
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
