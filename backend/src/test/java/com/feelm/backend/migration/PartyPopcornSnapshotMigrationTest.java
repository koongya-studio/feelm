package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PartyPopcornSnapshotMigrationTest {
    @Test
    void preservesPreviouslyExposedItemsWithoutInventingSnapshots() throws Exception {
        try (var database =
                MigrationPostgresSupport.databaseAtVersion("party_popcorn", "202609261200")) {
            try (Connection connection = database.connect();
                    Statement statement = connection.createStatement()) {
                statement.execute(
                        "INSERT INTO users(id,email,nickname,birth_date,gender,status) VALUES (9001,'legacy@feelm.test','legacy',DATE '1994-04-04','OTHER','ACTIVE')");
                statement.execute(
                        "INSERT INTO movies(id,tmdb_id,title) VALUES (9001,9001,'legacy movie')");
                statement.execute(
                        "INSERT INTO recommendation_sets(id,user_id,input_version,model_version,generated_at) VALUES (9001,9001,'legacy-input','legacy-model','2026-09-25T00:00:00Z')");
                statement.execute(
                        "INSERT INTO recommendation_items(set_id,movie_id,type,position,reason,exposed_at) VALUES (9001,9001,'TASTE',1,'{\"text\":\"legacy reason\"}','2026-09-25T00:01:00Z')");
            }
            var flyway =
                    Flyway.configure()
                            .dataSource(
                                    database.getJdbcUrl(),
                                    database.getUsername(),
                                    database.getPassword())
                            .target("202609271200")
                            .load();
            flyway.migrate();
            try (Connection connection = database.connect();
                    Statement statement = connection.createStatement()) {
                try (var rows =
                        statement.executeQuery(
                                "SELECT recommendation_mode,input_version FROM recommendation_sets WHERE id=9001")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("PERSONALIZED");
                    assertThat(rows.getString(2)).isEqualTo("legacy-input");
                }
                try (var rows =
                        statement.executeQuery(
                                "SELECT reason->>'text',exposed_at FROM recommendation_items WHERE set_id=9001")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("legacy reason");
                    assertThat(rows.getTimestamp(2).toInstant())
                            .isEqualTo(java.time.Instant.parse("2026-09-25T00:01:00Z"));
                }
                try (var rows =
                        statement.executeQuery("SELECT count(*) FROM party_popcorn_snapshots")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
            assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        }
    }
}
