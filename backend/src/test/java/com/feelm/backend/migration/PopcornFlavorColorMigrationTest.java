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

class PopcornFlavorColorMigrationTest {

    private static final String PREVIOUS_VERSION = "202609231400";
    private static final String MIGRATION_VERSION = "202609240005";

    @Test
    void updatesAllApprovedColors() throws Exception {
        try (var database = previousSchema()) {
            migrateTarget(database);

            assertThat(colorSnapshot(database))
                    .isEqualTo(
                            "FLAVOR_A:#F3DFB2,"
                                    + "FLAVOR_B:#5A3623,"
                                    + "FLAVOR_C:#CF8F4A,"
                                    + "FLAVOR_D:#D6CF9C,"
                                    + "FLAVOR_E:#F0E7D3,"
                                    + "FLAVOR_F:#E6AA52,"
                                    + "FLAVOR_G:#E7A79E,"
                                    + "FLAVOR_H:#B78A96");
            assertThat(targetFlyway(database).validateWithResult().validationSuccessful).isTrue();
        }
    }

    @Test
    void preservesIdentityAndOtherDisplayMetadata() throws Exception {
        try (var database = previousSchema()) {
            execute(
                    database,
                    """
                    UPDATE popcorn_flavors
                    SET id=42, display_name='보존 이름', sort_order=99,
                        representative_keywords='["preserved"]'
                    WHERE code='FLAVOR_A'
                    """);
            String before = nonColorSnapshot(database);

            migrateTarget(database);

            assertThat(nonColorSnapshot(database)).isEqualTo(before);
            assertThat(query(database, "SELECT color FROM popcorn_flavors WHERE code='FLAVOR_A'"))
                    .isEqualTo("#F3DFB2");
        }
    }

    @Test
    void rollsBackALateUpdateFailureAndCanRetry() throws Exception {
        try (var database = previousSchema()) {
            execute(
                    database,
                    """
                    CREATE FUNCTION reject_flavor_h_color() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      IF NEW.code='FLAVOR_H' THEN
                        RAISE EXCEPTION 'injected flavor color failure';
                      END IF;
                      RETURN NEW;
                    END $$;
                    CREATE TRIGGER reject_flavor_color BEFORE UPDATE ON popcorn_flavors
                      FOR EACH ROW EXECUTE FUNCTION reject_flavor_h_color();
                    """);
            String before = colorSnapshot(database);

            assertThatThrownBy(() -> migrateTarget(database))
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("injected flavor color failure");

            assertThat(colorSnapshot(database)).isEqualTo(before);
            assertThat(
                            query(
                                    database,
                                    "SELECT count(*) FROM flyway_schema_history WHERE version='"
                                            + MIGRATION_VERSION
                                            + "'"))
                    .isEqualTo("0");

            execute(
                    database,
                    """
                    DROP TRIGGER reject_flavor_color ON popcorn_flavors;
                    DROP FUNCTION reject_flavor_h_color()
                    """);
            migrateTarget(database);
            assertThat(query(database, "SELECT color FROM popcorn_flavors WHERE code='FLAVOR_H'"))
                    .isEqualTo("#B78A96");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "misbound", "extra"})
    void rejectsIncompleteOrConflictingCatalogWithoutPartialUpdates(String kind) throws Exception {
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
            String before = colorSnapshot(database);

            assertThatThrownBy(() -> migrateTarget(database))
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("Popcorn flavor identity conflict");

            assertThat(colorSnapshot(database)).isEqualTo(before);
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
        return MigrationPostgresSupport.databaseAtVersion("flavor_colors", PREVIOUS_VERSION);
    }

    private static void migrateTarget(MigrationPostgresSupport.MigrationTestDatabase database) {
        targetFlyway(database).migrate();
    }

    private static Flyway targetFlyway(MigrationPostgresSupport.MigrationTestDatabase database) {
        return Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .target(MIGRATION_VERSION)
                .load();
    }

    private static String nonColorSnapshot(MigrationPostgresSupport.MigrationTestDatabase database)
            throws SQLException {
        return query(
                database,
                """
                SELECT string_agg(
                  id || ':' || code || ':' || cluster_id || ':' || display_name || ':'
                    || sort_order || ':' || representative_keywords::text,
                  '|' ORDER BY code)
                FROM popcorn_flavors
                """);
    }

    private static String colorSnapshot(MigrationPostgresSupport.MigrationTestDatabase database)
            throws SQLException {
        return query(
                database,
                """
                SELECT string_agg(code || ':' || color, ',' ORDER BY cluster_id)
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
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
