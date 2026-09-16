package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PopcornFlavorSeedMigrationTest {

    private static final String SEED_VERSION = "202609161115";
    private static final Path SEED =
            Path.of("src/main/resources/db/migration/V202609161115__seed_popcorn_flavors.sql");

    @Test
    void seedsTheEightFixed439FlavorIdentities() throws Exception {
        try (var database = previousSchema()) {
            seedFlyway(database).migrate();

            assertThat(query(database, "SELECT count(*) FROM popcorn_flavors")).isEqualTo("8");
            assertThat(
                            query(
                                    database,
                                    """
                                    SELECT string_agg(
                                      code || ':' || cluster_id || ':' || sort_order,
                                      ',' ORDER BY sort_order)
                                    FROM popcorn_flavors
                                    """))
                    .isEqualTo(
                            "FLAVOR_A:0:1,FLAVOR_B:1:2,FLAVOR_C:2:3,FLAVOR_D:3:4,"
                                    + "FLAVOR_E:4:5,FLAVOR_F:5:6,FLAVOR_G:6:7,FLAVOR_H:7:8");
            assertThat(
                            query(
                                    database,
                                    """
                                    SELECT display_name || ':' || color || ':'
                                      || jsonb_array_length(representative_keywords)
                                    FROM popcorn_flavors WHERE code='FLAVOR_H'
                                    """))
                    .isEqualTo("치즈믹스:#D9C6EA:5");
            assertThat(seedFlyway(database).migrate().migrationsExecuted).isZero();
            assertThat(seedFlyway(database).validateWithResult().validationSuccessful).isTrue();
        }
    }

    @Test
    void preservesExistingMetadataForMatchingImmutableIdentities() throws Exception {
        try (var database = previousSchema()) {
            execute(
                    database,
                    """
                    INSERT INTO popcorn_flavors
                      (id,code,display_name,color,sort_order,cluster_id,representative_keywords)
                    VALUES (42,'FLAVOR_A','사용자 확정 이름','#010203',99,0,'["보존"]');
                    """);

            seedFlyway(database).migrate();

            assertThat(query(database, "SELECT count(*) FROM popcorn_flavors")).isEqualTo("8");
            assertThat(
                            query(
                                    database,
                                    """
                                    SELECT id || ':' || display_name || ':' || color || ':' || sort_order
                                    FROM popcorn_flavors WHERE code='FLAVOR_A'
                                    """))
                    .isEqualTo("42:사용자 확정 이름:#010203:99");
            execute(database, Files.readString(SEED));
            assertThat(query(database, "SELECT count(*) FROM popcorn_flavors")).isEqualTo("8");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"code", "cluster", "extra"})
    void rejectsIdentityConflictsWithoutPartialSeedRows(String kind) throws Exception {
        try (var database = previousSchema()) {
            String row =
                    switch (kind) {
                        case "code" -> "(42,'FLAVOR_A','wrong','#000000',1,7,'[]')";
                        case "cluster" -> "(42,'LEGACY','wrong','#000000',1,0,'[]')";
                        case "extra" -> "(42,'FLAVOR_I','wrong','#000000',1,8,'[]')";
                        default -> throw new IllegalArgumentException(kind);
                    };
            execute(
                    database,
                    """
                    INSERT INTO popcorn_flavors
                      (id,code,display_name,color,sort_order,cluster_id,representative_keywords)
                    VALUES
                    """
                            + row);

            assertThatThrownBy(() -> seedFlyway(database).migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("Popcorn flavor seed identity conflict");
            assertThat(query(database, "SELECT count(*) FROM popcorn_flavors")).isEqualTo("1");
            assertThat(
                            query(
                                    database,
                                    "SELECT count(*) FROM flyway_schema_history WHERE version='"
                                            + SEED_VERSION
                                            + "'"))
                    .isEqualTo("0");
        }
    }

    @Test
    void rollsBackALateInsertFailureAndCanRetry() throws Exception {
        try (var database = previousSchema()) {
            execute(
                    database,
                    """
                    CREATE FUNCTION reject_flavor_h() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      IF NEW.code='FLAVOR_H' THEN RAISE EXCEPTION 'injected flavor failure'; END IF;
                      RETURN NEW;
                    END $$;
                    CREATE TRIGGER reject_flavor_seed BEFORE INSERT ON popcorn_flavors
                      FOR EACH ROW EXECUTE FUNCTION reject_flavor_h();
                    """);

            assertThatThrownBy(() -> seedFlyway(database).migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("injected flavor failure");
            assertThat(query(database, "SELECT count(*) FROM popcorn_flavors")).isEqualTo("0");

            execute(
                    database,
                    "DROP TRIGGER reject_flavor_seed ON popcorn_flavors; "
                            + "DROP FUNCTION reject_flavor_h()");
            seedFlyway(database).migrate();
            assertThat(query(database, "SELECT count(*) FROM popcorn_flavors")).isEqualTo("8");
        }
    }

    private static MigrationPostgresSupport.MigrationTestDatabase previousSchema() {
        var database = MigrationPostgresSupport.database("flavor_seed");
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .target("202609151537")
                .load()
                .migrate();
        return database;
    }

    private static Flyway seedFlyway(MigrationPostgresSupport.MigrationTestDatabase database) {
        return Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .target(SEED_VERSION)
                .load();
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
