package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 항목별 공개 범위 컬럼 추가(S15P21E106-856) 마이그레이션 테스트.
 *
 * <p>이미 있는 계정 행이 지금까지와 같은 노출({@code PUBLIC})로 채워지는지, 계약에 없는 값이 CHECK에 걸리는지 본다.
 */
class UserItemVisibilityMigrationTest {

    private static final String PREVIOUS_VERSION = "202609251200";
    private static final String MIGRATION_VERSION = "202609251600";

    @Test
    void addsColumnsWithPublicDefaultForExistingRows() throws Exception {
        try (var database = previousSchema()) {
            execute(
                    database,
                    """
                    INSERT INTO users(email,nickname,birth_date,gender,status,visibility)
                    VALUES ('before@feelm.test','기존계정',DATE '1994-04-04','OTHER','ACTIVE','PRIVATE')
                    """);

            flyway(database).migrate();

            assertThat(
                            query(
                                    database,
                                    """
                                    SELECT string_agg(
                                      column_name || ':' || data_type || ':' || is_nullable
                                        || ':' || column_default,
                                      '|' ORDER BY column_name)
                                    FROM information_schema.columns
                                    WHERE table_schema='public'
                                      AND table_name='users'
                                      AND column_name IN
                                        ('frame_visibility','popcorn_visibility','review_visibility')
                                    """))
                    .isEqualTo(
                            "frame_visibility:character varying:NO:'PUBLIC'::character varying|"
                                    + "popcorn_visibility:character varying:NO:'PUBLIC'::character varying|"
                                    + "review_visibility:character varying:NO:'PUBLIC'::character varying");
            assertThat(
                            query(
                                    database,
                                    """
                                    SELECT visibility || ':' || frame_visibility || ':'
                                      || popcorn_visibility || ':' || review_visibility
                                    FROM users WHERE email='before@feelm.test'
                                    """))
                    .isEqualTo("PRIVATE:PUBLIC:PUBLIC:PUBLIC");
            assertThat(flyway(database).validateWithResult().validationSuccessful).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"frame_visibility", "popcorn_visibility", "review_visibility"})
    void rejectsValuesOutsideContract(String column) throws Exception {
        try (var database = previousSchema()) {
            flyway(database).migrate();
            execute(
                    database,
                    """
                    INSERT INTO users(email,nickname,birth_date,gender,status)
                    VALUES ('check@feelm.test','검사계정',DATE '1994-04-04','OTHER','ACTIVE')
                    """);

            // 와이어프레임의 `파티원만`은 계약 값이 아니다(S15P21E106-856 D-1).
            assertThatThrownBy(
                            () ->
                                    execute(
                                            database,
                                            "UPDATE users SET "
                                                    + column
                                                    + "='PARTY' WHERE email='check@feelm.test'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_users_" + column);
            assertThatThrownBy(
                            () ->
                                    execute(
                                            database,
                                            "UPDATE users SET "
                                                    + column
                                                    + "=NULL WHERE email='check@feelm.test'"))
                    .isInstanceOf(SQLException.class);
        }
    }

    private static MigrationPostgresSupport.MigrationTestDatabase previousSchema() {
        return MigrationPostgresSupport.databaseAtVersion("item_visibility", PREVIOUS_VERSION);
    }

    private static Flyway flyway(MigrationPostgresSupport.MigrationTestDatabase database) {
        return Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .target(MIGRATION_VERSION)
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
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }
}
