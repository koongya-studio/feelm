package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PopcornFlavorMetadataMigrationTest {

    private static final String PREVIOUS_VERSION = "202609161115";
    private static final String MIGRATION_VERSION = "202609161836";

    @Test
    void updatesAllApprovedDisplayMetadata() throws Exception {
        try (var database = previousSchema()) {
            database.flyway().migrate();

            assertThat(
                            query(
                                    database,
                                    """
                                    SELECT string_agg(
                                      code || ':' || display_name || ':' || color,
                                      ',' ORDER BY cluster_id)
                                    FROM popcorn_flavors
                                    """))
                    .isEqualTo(
                            "FLAVOR_A:오리지널 솔트:#F0D8B4,"
                                    + "FLAVOR_B:다크초코:#3C2418,"
                                    + "FLAVOR_C:솔티카라멜:#B46C24,"
                                    + "FLAVOR_D:피스타치오:#B4B46C,"
                                    + "FLAVOR_E:쿠키앤크림:#E4D8B4,"
                                    + "FLAVOR_F:체다치즈:#D87824,"
                                    + "FLAVOR_G:스트로베리:#D89C9C,"
                                    + "FLAVOR_H:블루베리:#A890B4");
        }
    }

    @Test
    void preservesImmutableIdentityAndInterpretationMetadata() throws Exception {
        try (var database = previousSchema()) {
            execute(
                    database,
                    """
                    UPDATE popcorn_flavors
                    SET id=42, sort_order=99, representative_keywords='["preserved"]'
                    WHERE code='FLAVOR_A'
                    """);
            String before = identityAndInterpretationSnapshot(database);

            database.flyway().migrate();

            assertThat(identityAndInterpretationSnapshot(database)).isEqualTo(before);
            assertThat(database.flyway().validateWithResult().validationSuccessful).isTrue();
        }
    }

    @Test
    void rollsBackALateUpdateFailureAndCanRetry() throws Exception {
        try (var database = previousSchema()) {
            execute(
                    database,
                    """
                    CREATE FUNCTION reject_flavor_h_metadata() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      IF NEW.code='FLAVOR_H' THEN
                        RAISE EXCEPTION 'injected flavor metadata failure';
                      END IF;
                      RETURN NEW;
                    END $$;
                    CREATE TRIGGER reject_flavor_metadata BEFORE UPDATE ON popcorn_flavors
                      FOR EACH ROW EXECUTE FUNCTION reject_flavor_h_metadata();
                    """);
            String before = displayMetadataSnapshot(database);

            assertThatThrownBy(() -> database.flyway().migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("injected flavor metadata failure");

            assertThat(displayMetadataSnapshot(database)).isEqualTo(before);
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
                    DROP TRIGGER reject_flavor_metadata ON popcorn_flavors;
                    DROP FUNCTION reject_flavor_h_metadata()
                    """);
            database.flyway().migrate();
            assertThat(
                            query(
                                    database,
                                    "SELECT display_name || ':' || color FROM popcorn_flavors "
                                            + "WHERE code='FLAVOR_H'"))
                    .isEqualTo("블루베리:#A890B4");
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
            String before = displayMetadataSnapshot(database);

            assertThatThrownBy(() -> database.flyway().migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("Popcorn flavor metadata identity conflict");

            assertThat(displayMetadataSnapshot(database)).isEqualTo(before);
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
        return MigrationPostgresSupport.databaseAtVersion("flavor_metadata", PREVIOUS_VERSION);
    }

    private static String identityAndInterpretationSnapshot(
            MigrationPostgresSupport.MigrationTestDatabase database) throws SQLException {
        return query(
                database,
                """
                SELECT string_agg(
                  id || ':' || code || ':' || cluster_id || ':' || sort_order || ':'
                    || representative_keywords::text,
                  '|' ORDER BY code)
                FROM popcorn_flavors
                """);
    }

    private static String displayMetadataSnapshot(
            MigrationPostgresSupport.MigrationTestDatabase database) throws SQLException {
        return query(
                database,
                """
                SELECT string_agg(
                  code || ':' || display_name || ':' || color,
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
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
