package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실제 PostgreSQL에 마이그레이션을 적용해 검증한다. 기본 테스트는 H2를 쓰지만 Flyway SQL은 PostgreSQL 문법이므로 H2에서 실행되지 않는다. 이
 * 테스트가 마이그레이션이 실제로 도는지 확인하는 유일한 지점이다.
 */
@DisplayName("V1 마이그레이션 — 확장과 공통 기준 테이블")
class V1MigrationTest {

    private static final MigrationPostgresSupport.MigrationTestDatabase DATABASE =
            MigrationPostgresSupport.database("v1");

    @BeforeAll
    static void migrate() {
        DATABASE.flyway().migrate();
    }

    @AfterAll
    static void dropDatabase() {
        DATABASE.close();
    }

    @Test
    @DisplayName("pgvector와 pg_trgm 확장이 생성된다")
    void createsRequiredExtensions() {
        assertThat(column("SELECT extname FROM pg_extension ORDER BY extname"))
                .contains("pg_trgm", "vector");
    }

    @Test
    @DisplayName("전 도메인이 참조하는 공통 기준 테이블이 생성된다")
    void createsBaseTables() {
        assertThat(
                        column(
                                "SELECT table_name FROM information_schema.tables"
                                        + " WHERE table_schema = 'public'"))
                .contains("users", "movies", "genres", "otts", "people", "popcorn_flavors");
    }

    @Test
    @DisplayName("initdb 로케일이 C.UTF-8로 적용된다")
    void usesCollationDecidedWithInfra() {
        assertThat(
                        column(
                                "SELECT datcollate FROM pg_database"
                                        + " WHERE datname = current_database()"))
                .containsExactly("C.UTF-8");
    }

    @Test
    @DisplayName("users의 이메일·정규화 닉네임·public_id가 유일하다")
    void enforcesUserUniqueness() throws SQLException {
        insertUser("dup@feelm.test", "중복닉");

        assertThatThrownBy(() -> insertUser("dup@feelm.test", "다른닉"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_users_email");

        // 공백 제거·소문자 정규화 값으로 비교하므로 대소문자와 공백만 다른 닉네임도 중복이다.
        assertThatThrownBy(() -> insertUser("other@feelm.test", " 중복 닉 "))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uix_users_nickname_normalized");
    }

    @Test
    @DisplayName("public_id는 지정하지 않아도 채워진다")
    void generatesPublicId() throws SQLException {
        insertUser("public-id@feelm.test", "공개식별자");

        assertThat(
                        column(
                                "SELECT public_id::text FROM users"
                                        + " WHERE email = 'public-id@feelm.test'"))
                .singleElement()
                .asString()
                .hasSize(36);
    }

    @Test
    @DisplayName("허용되지 않은 상태값은 저장되지 않는다")
    void rejectsUnknownStatus() {
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO users"
                                                + " (email, nickname, birth_date, gender, status)"
                                                + " VALUES ('bad-status@feelm.test', '잘못된상태',"
                                                + " DATE '1999-03-14', 'FEMALE', 'DELETED')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_users_status");
    }

    @Test
    @DisplayName("탈퇴 계정은 생년월일과 성별을 비울 수 있다")
    void allowsPersonalDataRemovalForWithdrawnUser() {
        assertThatCode(
                        () ->
                                execute(
                                        "INSERT INTO users"
                                                + " (email, nickname, birth_date, gender, status)"
                                                + " VALUES ('withdrawn@feelm.test', '탈퇴계정', NULL,"
                                                + " NULL, 'WITHDRAWN')"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("비탈퇴 계정은 생년월일이 필수다")
    void requiresBirthDateForNonWithdrawnUser() {
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO users"
                                                + " (email, nickname, birth_date, gender, status)"
                                                + " VALUES ('active-without-birth@feelm.test',"
                                                + " '생년월일없음', NULL, 'FEMALE', 'ACTIVE')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_users_birth_date_required_unless_withdrawn");
    }

    @Test
    @DisplayName("비탈퇴 계정은 성별이 필수다")
    void requiresGenderForNonWithdrawnUser() {
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO users (email, nickname, birth_date, status)"
                                                + " VALUES ('active-without-gender@feelm.test',"
                                                + " '성별없음', DATE '1999-03-14', 'ACTIVE')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_users_gender_required_unless_withdrawn");
    }

    @Test
    @DisplayName("마이그레이션을 다시 적용해도 실패하지 않는다")
    void isSafeToReapply() {
        assertThat(DATABASE.flyway().migrate().migrationsExecuted).isZero();
    }

    // --- helpers ---------------------------------------------------------

    private static void insertUser(String email, String nickname) throws SQLException {
        execute(
                "INSERT INTO users (email, nickname, birth_date, gender, status) VALUES ('"
                        + email
                        + "', '"
                        + nickname
                        + "', DATE '1999-03-14', 'FEMALE', 'ACTIVE')");
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> column(String sql) {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
            return values;
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private static Connection connect() throws SQLException {
        return DATABASE.connect();
    }
}
