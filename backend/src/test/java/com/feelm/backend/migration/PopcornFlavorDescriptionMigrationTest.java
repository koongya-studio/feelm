package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PopcornFlavorDescriptionMigrationTest {

    private static final String PREVIOUS_VERSION = "202609240005";
    private static final String MIGRATION_VERSION = "202609241438";

    @Test
    void addsDescriptionsAfterTheLatestAppliedMigration() throws Exception {
        try (var database = previousSchema()) {
            assertThat(
                            query(
                                    database,
                                    "SELECT count(*) FROM flyway_schema_history WHERE version='"
                                            + PREVIOUS_VERSION
                                            + "'"))
                    .isEqualTo("1");
            assertThat(
                            query(
                                    database,
                                    """
                                    SELECT count(*)
                                    FROM information_schema.columns
                                    WHERE table_schema='public'
                                      AND table_name='popcorn_flavors'
                                      AND column_name='description'
                                    """))
                    .isEqualTo("0");

            descriptionFlyway(database).migrate();

            assertThat(
                            query(
                                    database,
                                    """
                                    SELECT data_type || ':' || is_nullable
                                    FROM information_schema.columns
                                    WHERE table_schema='public'
                                      AND table_name='popcorn_flavors'
                                      AND column_name='description'
                                    """))
                    .isEqualTo("text:NO");
            assertThat(
                            query(
                                    database,
                                    """
                                    SELECT string_agg(code || ':' || description, '|' ORDER BY cluster_id)
                                    FROM popcorn_flavors
                                    """))
                    .isEqualTo(
                            "FLAVOR_A:있었던 일을 그대로 담은 이야기에 끌려요|"
                                    + "FLAVOR_B:서늘하고 낯선 세계를 즐겨요|"
                                    + "FLAVOR_C:웃기면서 씁쓸한 관계 이야기를 봐요|"
                                    + "FLAVOR_D:사람과 시대를 깊게 들여다봐요|"
                                    + "FLAVOR_E:쫓고 쫓기는 긴장감을 즐겨요|"
                                    + "FLAVOR_F:가볍게 웃고 기분 좋아지는 영화를 봐요|"
                                    + "FLAVOR_G:상상으로 그린 세계를 여행해요|"
                                    + "FLAVOR_H:크고 시원한 모험에 빠져요");
            assertThat(descriptionFlyway(database).validateWithResult().validationSuccessful)
                    .isTrue();
        }
    }

    @Test
    void preservesExistingFlavorMetadata() throws Exception {
        try (var database = previousSchema()) {
            execute(
                    database,
                    """
                    UPDATE popcorn_flavors
                    SET id=42, sort_order=99, representative_keywords='["preserved"]'
                    WHERE code='FLAVOR_A'
                    """);
            String before = metadataSnapshot(database);

            descriptionFlyway(database).migrate();

            assertThat(metadataSnapshot(database)).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "misbound", "extra"})
    void rejectsIncompleteOrConflictingCatalogWithoutPartialChanges(String kind) throws Exception {
        try (var database = previousSchema()) {
            switch (kind) {
                case "missing" ->
                        execute(database, "DELETE FROM popcorn_flavors WHERE code='FLAVOR_H'");
                case "misbound" ->
                        execute(
                                database,
                                "UPDATE popcorn_flavors SET cluster_id=8 WHERE code='FLAVOR_H'");
                case "extra" ->
                        execute(
                                database,
                                """
                                INSERT INTO popcorn_flavors
                                  (code,display_name,color,sort_order,cluster_id,representative_keywords)
                                VALUES ('FLAVOR_I','extra','#000000',9,8,'[]')
                                """);
                default -> throw new IllegalArgumentException(kind);
            }

            assertThatThrownBy(() -> descriptionFlyway(database).migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("Popcorn flavor description identity conflict");

            assertThat(
                            query(
                                    database,
                                    """
                                    SELECT count(*)
                                    FROM information_schema.columns
                                    WHERE table_schema='public'
                                      AND table_name='popcorn_flavors'
                                      AND column_name='description'
                                    """))
                    .isEqualTo("0");
            assertThat(
                            query(
                                    database,
                                    "SELECT count(*) FROM flyway_schema_history WHERE version='"
                                            + MIGRATION_VERSION
                                            + "'"))
                    .isEqualTo("0");
        }
    }

    private static MigrationPostgresSupport.MigrationTestDatabase previousSchema() {
        return MigrationPostgresSupport.databaseAtVersion("flavor_description", PREVIOUS_VERSION);
    }

    private static Flyway descriptionFlyway(
            MigrationPostgresSupport.MigrationTestDatabase database) {
        return Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .target(MIGRATION_VERSION)
                .load();
    }

    private static String metadataSnapshot(MigrationPostgresSupport.MigrationTestDatabase database)
            throws SQLException {
        return query(
                database,
                """
                SELECT string_agg(
                  id || ':' || code || ':' || display_name || ':' || color || ':' || sort_order
                    || ':' || cluster_id || ':' || representative_keywords::text,
                  '|' ORDER BY code)
                FROM popcorn_flavors
                """);
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
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }
}
