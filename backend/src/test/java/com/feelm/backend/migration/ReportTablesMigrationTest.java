package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("반기 리포트 저장 테이블 마이그레이션")
class ReportTablesMigrationTest {

    private static final String PREVIOUS_VERSION = "202609181000";

    private static final MigrationPostgresSupport.MigrationTestDatabase DATABASE =
            MigrationPostgresSupport.database("report_tables");

    @BeforeAll
    static void migrateFreshDatabase() {
        DATABASE.flyway().migrate();
    }

    @AfterAll
    static void dropDatabase() {
        DATABASE.close();
    }

    @Test
    @DisplayName("T1 두 테이블의 컬럼, 제약, 기본값과 인덱스를 생성한다")
    void createsContractSchema() throws SQLException {
        assertThat(columns(DATABASE, "taste_reports"))
                .containsExactly(
                        "id:bigint:-:NO",
                        "user_id:bigint:-:NO",
                        "year:smallint:-:NO",
                        "half:character varying:2:NO",
                        "status:character varying:10:NO",
                        "content:jsonb:-:NO",
                        "created_at:timestamp with time zone:-:NO",
                        "issued_at:timestamp with time zone:-:YES",
                        "source_fingerprint:character varying:64:YES",
                        "source_frames:jsonb:-:YES");
        assertThat(columns(DATABASE, "report_images"))
                .containsExactly(
                        "id:bigint:-:NO",
                        "report_id:bigint:-:NO",
                        "storage_key:text:-:NO",
                        "format:character varying:3:NO",
                        "created_at:timestamp with time zone:-:NO");
        assertThat(constraints(DATABASE, "taste_reports"))
                .containsExactlyInAnyOrder(
                        "taste_reports_pkey",
                        "fk_taste_reports_user_id",
                        "ck_taste_reports_half",
                        "ck_taste_reports_status");
        assertThat(constraints(DATABASE, "report_images"))
                .containsExactlyInAnyOrder(
                        "report_images_pkey",
                        "fk_report_images_report_id",
                        "ck_report_images_format");
        assertThat(
                        stringValue(
                                DATABASE,
                                "SELECT indexdef FROM pg_indexes"
                                        + " WHERE indexname = 'uk_taste_reports_legacy_period'"))
                .contains(
                        "UNIQUE INDEX",
                        "(user_id, year, half)",
                        "WHERE (source_fingerprint IS NULL)");
        assertThat(
                        stringValue(
                                DATABASE,
                                "SELECT indexdef FROM pg_indexes"
                                        + " WHERE indexname = 'ix_taste_reports_user_created'"))
                .contains("(user_id, created_at DESC, id DESC)");
        assertThat(
                        stringValue(
                                DATABASE,
                                "SELECT confrelid::regclass::text FROM pg_constraint"
                                        + " WHERE conname = 'fk_taste_reports_user_id'"))
                .isEqualTo("users");
        assertThat(
                        stringValue(
                                DATABASE,
                                "SELECT confrelid::regclass::text FROM pg_constraint"
                                        + " WHERE conname = 'fk_report_images_report_id'"))
                .isEqualTo("taste_reports");
        assertThat(
                        stringValue(
                                DATABASE,
                                "SELECT indexdef FROM pg_indexes"
                                        + " WHERE schemaname = 'public'"
                                        + " AND indexname = 'ix_report_images_report_id'"))
                .contains("ON public.report_images USING btree (report_id)");
        assertThat(
                        stringValue(
                                DATABASE,
                                "SELECT identity_generation FROM information_schema.columns"
                                        + " WHERE table_name = 'taste_reports' AND column_name = 'id'"))
                .isEqualTo("BY DEFAULT");
        assertThat(
                        stringValue(
                                DATABASE,
                                "SELECT identity_generation FROM information_schema.columns"
                                        + " WHERE table_name = 'report_images' AND column_name = 'id'"))
                .isEqualTo("BY DEFAULT");
        assertThat(
                        stringValue(
                                DATABASE,
                                "SELECT column_default FROM information_schema.columns"
                                        + " WHERE table_name = 'taste_reports'"
                                        + " AND column_name = 'created_at'"))
                .isEqualTo("now()");
        assertThat(
                        stringValue(
                                DATABASE,
                                "SELECT column_default FROM information_schema.columns"
                                        + " WHERE table_name = 'report_images'"
                                        + " AND column_name = 'created_at'"))
                .isEqualTo("now()");
        assertThat(
                        stringValue(
                                DATABASE,
                                "SELECT column_default FROM information_schema.columns"
                                        + " WHERE table_name = 'taste_reports'"
                                        + " AND column_name = 'status'"))
                .isNull();
        assertThat(
                        stringValue(
                                DATABASE,
                                "SELECT confdeltype::text FROM pg_constraint"
                                        + " WHERE conname = 'fk_taste_reports_user_id'"))
                .isEqualTo("r");
        assertThat(
                        stringValue(
                                DATABASE,
                                "SELECT confdeltype::text FROM pg_constraint"
                                        + " WHERE conname = 'fk_report_images_report_id'"))
                .isEqualTo("c");
        assertThat(DATABASE.flyway().validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    @DisplayName("T2 수동 복수 생성, 기존 반기 유일성, 참조 무결성과 이미지 cascade를 강제한다")
    void enforcesReportAndImageRelations() throws SQLException {
        long userId =
                longValue(
                        DATABASE,
                        "INSERT INTO users(email,nickname,birth_date,gender,status)"
                                + " VALUES ('report-relations@feelm.test','리포트관계',"
                                + " DATE '1995-05-05','OTHER','ACTIVE') RETURNING id");
        long otherUserId =
                longValue(
                        DATABASE,
                        "INSERT INTO users(email,nickname,birth_date,gender,status)"
                                + " VALUES ('report-other@feelm.test','다른리포트사용자',"
                                + " DATE '1995-05-05','OTHER','ACTIVE') RETURNING id");
        long firstReport = insertReport(DATABASE, userId, 2026, "H1", "GENERATING");
        long secondReport = insertReport(DATABASE, userId, 2026, "H2", "READY");
        long nextYearReport = insertReport(DATABASE, userId, 2027, "H1", "READY");
        long otherUserReport = insertReport(DATABASE, otherUserId, 2026, "H1", "READY");
        execute(
                DATABASE,
                "INSERT INTO report_images(report_id,storage_key,format) VALUES ("
                        + firstReport
                        + ", 'private/reports/card.png', 'PNG')");
        execute(
                DATABASE,
                "INSERT INTO report_images(report_id,storage_key,format) VALUES ("
                        + firstReport
                        + ", 'private/reports/card-again.png', 'PNG')");
        execute(
                DATABASE,
                "INSERT INTO report_images(report_id,storage_key,format) VALUES ("
                        + secondReport
                        + ", 'private/reports/other-card.png', 'PNG')");

        assertThatThrownBy(() -> insertReport(DATABASE, userId, 2026, "H1", "READY"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_taste_reports_legacy_period");
        for (int snapshot = 0; snapshot < 2; snapshot++) {
            execute(
                    DATABASE,
                    "INSERT INTO taste_reports(user_id,year,half,status,content,"
                            + "source_fingerprint,source_frames) VALUES ("
                            + userId
                            + ",2026,'H1','READY','{}'::jsonb,'fingerprint','{}'::jsonb)");
        }
        assertThat(
                        longValue(
                                DATABASE,
                                "SELECT count(*) FROM taste_reports WHERE user_id = "
                                        + userId
                                        + " AND year = 2026 AND half = 'H1'"))
                .isEqualTo(3);
        assertThatThrownBy(() -> insertReport(DATABASE, userId, 2026, "H3", "READY"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_taste_reports_half");
        assertThatThrownBy(() -> insertReport(DATABASE, userId, 2026, "HX", "READY"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_taste_reports_half");
        assertThatThrownBy(() -> insertReport(DATABASE, userId, 2026, "H1", "FAILED"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_taste_reports_status");
        assertThatThrownBy(() -> insertReport(DATABASE, userId, 2026, "H1", "PAUSED"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_taste_reports_status");
        assertThatThrownBy(() -> insertReport(DATABASE, -1, 2026, "H1", "READY"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_taste_reports_user_id");
        assertThatThrownBy(
                        () ->
                                execute(
                                        DATABASE,
                                        "INSERT INTO report_images(report_id,storage_key,format)"
                                                + " VALUES ("
                                                + firstReport
                                                + ", 'private/reports/invalid.png', 'GIF')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_report_images_format");
        assertThatThrownBy(
                        () ->
                                execute(
                                        DATABASE,
                                        "INSERT INTO report_images(report_id,storage_key,format)"
                                                + " VALUES ("
                                                + firstReport
                                                + ", 'private/reports/extra.png', 'JPG')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_report_images_format");
        assertThatThrownBy(
                        () ->
                                execute(
                                        DATABASE,
                                        "INSERT INTO report_images(report_id,storage_key,format)"
                                                + " VALUES (-1, 'private/reports/missing.png', 'PNG')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_report_images_report_id");
        assertThatThrownBy(() -> execute(DATABASE, "DELETE FROM users WHERE id = " + userId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_taste_reports_user_id");

        execute(DATABASE, "DELETE FROM taste_reports WHERE id = " + firstReport);
        assertThat(
                        longValue(
                                DATABASE,
                                "SELECT count(*) FROM report_images WHERE report_id = "
                                        + firstReport))
                .isZero();
        assertThat(
                        longValue(
                                DATABASE,
                                "SELECT count(*) FROM report_images WHERE report_id = "
                                        + secondReport))
                .isOne();
        assertThat(
                        longValue(
                                DATABASE,
                                "SELECT count(*) FROM taste_reports WHERE id = " + secondReport))
                .isOne();
        assertThat(
                        longValue(
                                DATABASE,
                                "SELECT count(*) FROM taste_reports WHERE id IN ("
                                        + nextYearReport
                                        + ","
                                        + otherUserReport
                                        + ")"))
                .isEqualTo(2);
        assertThat(longValue(DATABASE, "SELECT count(*) FROM users WHERE id = " + userId)).isOne();
        assertThat(longValue(DATABASE, "SELECT count(*) FROM users WHERE id = " + otherUserId))
                .isOne();
    }

    @Test
    @DisplayName("T3 기존 평가 데이터가 있는 DB를 업그레이드하고 재적용해도 보존한다")
    void upgradesExistingDatabaseWithoutDataLoss() throws SQLException {
        try (MigrationPostgresSupport.MigrationTestDatabase upgradeDatabase =
                MigrationPostgresSupport.database("report_upgrade")) {
            Flyway.configure()
                    .dataSource(
                            upgradeDatabase.getJdbcUrl(),
                            upgradeDatabase.getUsername(),
                            upgradeDatabase.getPassword())
                    .target(PREVIOUS_VERSION)
                    .load()
                    .migrate();
            long userId =
                    longValue(
                            upgradeDatabase,
                            "INSERT INTO users(email,nickname,birth_date,gender,status)"
                                    + " VALUES ('report-upgrade@feelm.test','리포트업그레이드',"
                                    + " DATE '1995-05-05','OTHER','ACTIVE') RETURNING id");
            long movieId =
                    longValue(
                            upgradeDatabase,
                            "INSERT INTO movies(tmdb_id,title)"
                                    + " VALUES (554001,'기존 평가 영화') RETURNING id");
            long ratingId =
                    longValue(
                            upgradeDatabase,
                            "INSERT INTO ratings(user_id,movie_id,score,comment,visibility)"
                                    + " VALUES ("
                                    + userId
                                    + ","
                                    + movieId
                                    + ",4.5,'기존 평가','PUBLIC') RETURNING id");

            assertThat(upgradeDatabase.flyway().migrate().migrationsExecuted).isPositive();
            assertThat(
                            longValue(
                                    upgradeDatabase,
                                    "SELECT user_id FROM ratings WHERE id = " + ratingId))
                    .isEqualTo(userId);
            assertThat(
                            longValue(
                                    upgradeDatabase,
                                    "SELECT movie_id FROM ratings WHERE id = " + ratingId))
                    .isEqualTo(movieId);
            assertThat(
                            stringValue(
                                    upgradeDatabase,
                                    "SELECT score::text FROM ratings WHERE id = " + ratingId))
                    .isEqualTo("4.5");
            assertThat(longValue(upgradeDatabase, "SELECT count(*) FROM taste_reports")).isZero();
            assertThat(longValue(upgradeDatabase, "SELECT count(*) FROM report_images")).isZero();
            assertThat(upgradeDatabase.flyway().migrate().migrationsExecuted)
                    .as("재적용해도 추가로 실행되는 마이그레이션은 없다")
                    .isZero();
            assertThat(upgradeDatabase.flyway().validateWithResult().validationSuccessful).isTrue();
        }
    }

    private static long insertReport(
            MigrationPostgresSupport.MigrationTestDatabase database,
            long userId,
            int year,
            String half,
            String status)
            throws SQLException {
        return longValue(
                database,
                "INSERT INTO taste_reports(user_id,year,half,status,content) VALUES ("
                        + userId
                        + ","
                        + year
                        + ",'"
                        + half
                        + "','"
                        + status
                        + "','{}'::jsonb) RETURNING id");
    }

    private static List<String> columns(
            MigrationPostgresSupport.MigrationTestDatabase database, String table)
            throws SQLException {
        return strings(
                database,
                "SELECT column_name || ':' || data_type || ':'"
                        + " || COALESCE(character_maximum_length::text, '-') || ':' || is_nullable"
                        + " FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND table_name = '"
                        + table
                        + "' ORDER BY ordinal_position");
    }

    private static List<String> constraints(
            MigrationPostgresSupport.MigrationTestDatabase database, String table)
            throws SQLException {
        return strings(
                database,
                "SELECT conname FROM pg_constraint WHERE conrelid = 'public."
                        + table
                        + "'::regclass");
    }

    private static String stringValue(
            MigrationPostgresSupport.MigrationTestDatabase database, String sql)
            throws SQLException {
        try (Connection connection = database.connect();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static long longValue(
            MigrationPostgresSupport.MigrationTestDatabase database, String sql)
            throws SQLException {
        try (Connection connection = database.connect();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static List<String> strings(
            MigrationPostgresSupport.MigrationTestDatabase database, String sql)
            throws SQLException {
        try (Connection connection = database.connect();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (result.next()) {
                values.add(result.getString(1));
            }
            return values;
        }
    }

    private static void execute(MigrationPostgresSupport.MigrationTestDatabase database, String sql)
            throws SQLException {
        try (Connection connection = database.connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
