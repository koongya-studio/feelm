package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OttCatalogSeedMigrationTest {

    private static final String SEED_VERSION = "202609142240";
    private static final Path SEED =
            Path.of("src/main/resources/db/migration/V202609142240__seed_approved_ott_catalog.sql");

    @Test
    void preservesFullyLoadedCatalogMetadataAndAllReferences() throws Exception {
        try (var database = previousSchema()) {
            execute(
                    database,
                    """
                    INSERT INTO otts(id,code,name,logo_url,tmdb_provider_id) VALUES
                      (42,'NETFLIX','Existing Netflix',NULL,8),
                      (51,'DISNEY_PLUS','Existing Disney','/old-disney.png',337),
                      (62,'WATCHA','Existing Watcha',NULL,97),
                      (73,'WAVVE','Existing Wavve',NULL,356),
                      (84,'TVING','Existing Tving',NULL,1883),
                      (99,'OTHER','Other provider',NULL,9999);
                    """);
            references(database, 42);
            String before = snapshot(database);

            assertThat(database.flyway().migrate().migrationsExecuted).isPositive();
            assertThat(
                            query(
                                    database,
                                    "SELECT count(*) FROM flyway_schema_history WHERE version='"
                                            + SEED_VERSION
                                            + "' AND success"))
                    .isEqualTo("1");
            assertThat(snapshot(database)).isEqualTo(before);
            assertThat(database.flyway().migrate().migrationsExecuted).isZero();
            execute(database, Files.readString(SEED));
            assertThat(snapshot(database)).isEqualTo(before);
        }
    }

    @Test
    void fillsPartialCatalogWithoutReassigningOccupiedIdsOrExistingMetadata() throws Exception {
        try (var database = previousSchema()) {
            execute(
                    database,
                    """
                    INSERT INTO otts(id,code,name,logo_url,tmdb_provider_id) VALUES
                      (1,'NETFLIX','Original name',NULL,8),
                      (2,'OTHER','Unrelated',NULL,9999),
                      (4,'WATCHA','Original Watcha','/original.png',97);
                    """);
            references(database, 1);
            String before = existingRows(database);

            database.flyway().migrate();

            assertThat(existingRows(database)).isEqualTo(before);
            assertThat(query(database, "SELECT count(*) FROM otts")).isEqualTo("6");
            assertThat(query(database, "SELECT id FROM otts WHERE code='DISNEY_PLUS'"))
                    .isEqualTo("3");
            assertThat(query(database, "SELECT id FROM otts WHERE code='WAVVE'")).isEqualTo("5");
            String complete = snapshot(database);
            assertThat(database.flyway().migrate().migrationsExecuted).isZero();
            execute(database, Files.readString(SEED));
            assertThat(snapshot(database)).isEqualTo(complete);
        }
    }

    @Test
    void normalizesLaggingSequenceBeforeNextDefaultInsert() throws Exception {
        try (var database = previousSchema()) {
            execute(
                    database,
                    """
                    INSERT INTO otts(id,code,name,logo_url,tmdb_provider_id) VALUES
                      (1,'NETFLIX','Existing Netflix',NULL,8),
                      (3,'WATCHA','Existing Watcha',NULL,97),
                      (4,'WAVVE','Existing Wavve',NULL,356),
                      (5,'TVING','Existing Tving',NULL,1883),
                      (99,'LEGACY','Legacy provider',NULL,9099);
                    """);
            references(database, 3);
            String referencesBefore = referenceSnapshot(database);

            assertThat(sequenceState(database)).isEqualTo("1|false");

            database.flyway().migrate();
            execute(
                    database,
                    """
                    INSERT INTO otts(code,name,logo_url,tmdb_provider_id)
                    VALUES ('OTHER','Other provider',NULL,9999)
                    """);

            assertThat(query(database, "SELECT id FROM otts WHERE code='OTHER'")).isEqualTo("100");
            assertThat(
                            query(
                                    database,
                                    """
                                    SELECT string_agg(code || ':' || id, ',' ORDER BY code)
                                    FROM otts
                                    WHERE code IN (
                                      'NETFLIX','DISNEY_PLUS','WATCHA','WAVVE','TVING')
                                    """))
                    .isEqualTo("DISNEY_PLUS:2,NETFLIX:1,TVING:5,WATCHA:3,WAVVE:4");
            assertThat(referenceSnapshot(database)).isEqualTo(referencesBefore);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"code", "provider", "cross", "late"})
    void rejectsIdentityConflictsAndRollsBackEverySeedRow(String kind) throws Exception {
        try (var database = previousSchema()) {
            String rows =
                    switch (kind) {
                        case "code" -> "(42,'NETFLIX','Wrong provider',NULL,9999)";
                        case "provider" -> "(42,'OTHER','Wrong code',NULL,8)";
                        case "cross" ->
                                "(42,'NETFLIX','Cross 1',NULL,337),(43,'DISNEY_PLUS','Cross 2',NULL,8)";
                        case "late" -> "(42,'TVING','Late conflict',NULL,9999)";
                        default -> throw new IllegalArgumentException(kind);
                    };
            execute(
                    database,
                    "INSERT INTO otts(id,code,name,logo_url,tmdb_provider_id) VALUES " + rows);
            references(database, 42);
            String before = snapshot(database);

            assertThatThrownBy(() -> database.flyway().migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("OTT seed identity conflict");
            assertThat(snapshot(database)).isEqualTo(before);
            assertThat(
                            query(
                                    database,
                                    "SELECT count(*) FROM flyway_schema_history WHERE version='"
                                            + SEED_VERSION
                                            + "'"))
                    .isEqualTo("0");
            // Retrying before an authorized correction must still fail without partial catalog
            // writes.
            assertThatThrownBy(() -> database.flyway().migrate())
                    .isInstanceOf(FlywayException.class);
            assertThat(snapshot(database)).isEqualTo(before);
        }
    }

    @Test
    void rollsBackLateSqlFailureAndCanRetryWithoutRepairOrReferenceChanges() throws Exception {
        try (var database = previousSchema()) {
            execute(
                    database,
                    """
                    INSERT INTO otts(id,code,name,logo_url,tmdb_provider_id)
                    VALUES (42,'NETFLIX','Preserved',NULL,8);
                    CREATE FUNCTION reject_seed_test_row() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      IF NEW.code='TVING' THEN RAISE EXCEPTION 'injected seed failure'; END IF;
                      RETURN NEW;
                    END $$;
                    CREATE TRIGGER reject_seed_test BEFORE INSERT ON otts
                      FOR EACH ROW EXECUTE FUNCTION reject_seed_test_row();
                    """);
            references(database, 42);
            String before = snapshot(database);
            assertThatThrownBy(() -> database.flyway().migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("injected seed failure");
            assertThat(snapshot(database)).isEqualTo(before);

            execute(
                    database,
                    "DROP TRIGGER reject_seed_test ON otts; DROP FUNCTION reject_seed_test_row()");
            database.flyway().migrate();
            assertThat(query(database, "SELECT count(*) FROM otts")).isEqualTo("5");
            assertThat(query(database, "SELECT id FROM otts WHERE code='NETFLIX'")).isEqualTo("42");
            assertThat(query(database, "SELECT count(*) FROM watch_records WHERE ott_id=42"))
                    .isEqualTo("1");
            assertThat(database.flyway().validateWithResult().validationSuccessful).isTrue();
        }
    }

    @Test
    void neverMovesAnAdvancedSequenceBackwards() throws Exception {
        try (var database = previousSchema()) {
            execute(
                    database,
                    """
                    INSERT INTO otts(id,code,name,logo_url,tmdb_provider_id) VALUES
                      (11,'NETFLIX','Existing Netflix',NULL,8),
                      (22,'DISNEY_PLUS','Existing Disney',NULL,337),
                      (33,'WATCHA','Existing Watcha',NULL,97),
                      (44,'WAVVE','Existing Wavve',NULL,356),
                      (55,'TVING','Existing Tving',NULL,1883),
                      (99,'OTHER','Other provider',NULL,9999);
                    """);
            references(database, 11);
            String before = snapshot(database);
            execute(database, "SELECT setval(pg_get_serial_sequence('otts','id'),1000,true)");

            database.flyway().migrate();
            assertThat(snapshot(database)).isEqualTo(before);
            assertThat(sequenceState(database)).isEqualTo("1000|true");
            assertThat(database.flyway().migrate().migrationsExecuted).isZero();
            assertThat(query(database, "SELECT nextval(pg_get_serial_sequence('otts','id'))"))
                    .isEqualTo("1001");
        }
    }

    @Test
    void advancesUncalledSequenceAtOccupiedMaximum() throws Exception {
        try (var database = previousSchema()) {
            execute(
                    database,
                    """
                    INSERT INTO otts(id,code,name,logo_url,tmdb_provider_id) VALUES
                      (1,'NETFLIX','Existing Netflix',NULL,8),
                      (2,'DISNEY_PLUS','Existing Disney',NULL,337),
                      (3,'WATCHA','Existing Watcha',NULL,97),
                      (4,'WAVVE','Existing Wavve',NULL,356),
                      (5,'TVING','Existing Tving',NULL,1883);
                    SELECT setval(pg_get_serial_sequence('otts','id'),5,false);
                    """);
            String before = snapshot(database);
            assertThat(sequenceState(database)).isEqualTo("5|false");

            database.flyway().migrate();

            assertThat(snapshot(database)).isEqualTo(before);
            assertThat(sequenceState(database)).isEqualTo("5|true");
            assertThat(query(database, "SELECT nextval(pg_get_serial_sequence('otts','id'))"))
                    .isEqualTo("6");
        }
    }

    private static MigrationPostgresSupport.MigrationTestDatabase previousSchema() {
        var database = MigrationPostgresSupport.database("ott_seed");
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .target("202609140120")
                .load()
                .migrate();
        return database;
    }

    private static void references(
            MigrationPostgresSupport.MigrationTestDatabase database, long ottId)
            throws SQLException {
        execute(
                database,
                """
                INSERT INTO users(id,email,nickname,birth_date,gender,status)
                  VALUES (700,'seed@example.invalid','seed','2000-01-01','OTHER','ACTIVE');
                INSERT INTO movies(id,tmdb_id,title) VALUES (900,900,'Existing movie');
                INSERT INTO user_otts(user_id,ott_id) VALUES (700,%1$d);
                INSERT INTO movie_ott_providers(movie_id,ott_id) VALUES (900,%1$d);
                INSERT INTO watch_records(user_id,movie_id,ott_id,status)
                  VALUES (700,900,%1$d,'WATCHED_UNRATED');
                """
                        .formatted(ottId));
    }

    private static String snapshot(MigrationPostgresSupport.MigrationTestDatabase database)
            throws SQLException {
        return query(
                database,
                """
                SELECT json_build_array(
                  (SELECT json_agg(t ORDER BY id) FROM otts t),
                  (SELECT json_agg(t ORDER BY user_id,ott_id) FROM user_otts t),
                  (SELECT json_agg(t ORDER BY movie_id,ott_id) FROM movie_ott_providers t),
                  (SELECT json_agg(t ORDER BY id) FROM watch_records t))
                """);
    }

    private static String existingRows(MigrationPostgresSupport.MigrationTestDatabase database)
            throws SQLException {
        return query(
                database,
                """
                SELECT json_build_array(
                  (SELECT json_agg(t ORDER BY id) FROM otts t WHERE id IN (1,2,4)),
                  (SELECT json_agg(t ORDER BY user_id,ott_id) FROM user_otts t),
                  (SELECT json_agg(t ORDER BY movie_id,ott_id) FROM movie_ott_providers t),
                  (SELECT json_agg(t ORDER BY id) FROM watch_records t))
                """);
    }

    private static String referenceSnapshot(MigrationPostgresSupport.MigrationTestDatabase database)
            throws SQLException {
        return query(
                database,
                """
                SELECT json_build_array(
                  (SELECT json_agg(t ORDER BY user_id,ott_id) FROM user_otts t),
                  (SELECT json_agg(t ORDER BY movie_id,ott_id) FROM movie_ott_providers t),
                  (SELECT json_agg(t ORDER BY id) FROM watch_records t))
                """);
    }

    private static String sequenceState(MigrationPostgresSupport.MigrationTestDatabase database)
            throws SQLException {
        String sequence = query(database, "SELECT pg_get_serial_sequence('public.otts','id')");
        return query(
                database, "SELECT last_value::text || '|' || is_called::text FROM " + sequence);
    }

    private static void execute(MigrationPostgresSupport.MigrationTestDatabase database, String sql)
            throws SQLException {
        try (var connection = database.connect();
                var statement = connection.createStatement()) {
            statement.execute(sql);
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
