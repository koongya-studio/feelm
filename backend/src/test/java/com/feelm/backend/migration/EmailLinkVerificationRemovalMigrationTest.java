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
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * 링크 인증 제거 마이그레이션이 잔존 데이터를 실제로 정리하는지 검증한다(S15P21E106-604).
 *
 * <p>다른 마이그레이션 테스트와 달리 <b>두 번에 나눠</b> 적용한다. 정리 대상이 이 마이그레이션 <i>이전</i> 흐름이 남긴 행이라, 한 번에 전부 적용하면 지울
 * 행을 만들 자리가 없다. 직전 버전까지 적용해 미인증 계정과 그 의존 행을 만든 뒤 나머지를 적용한다.
 *
 * <p>실행에 Docker가 필요하다. Flyway가 기동 로그를 표준 출력에 쏟으므로 출력 캡처를 쓰는 테스트와 같은 잠금을 잡는다. 잡지 않으면 그 테스트들이 이 클래스의
 * 로그를 자기 출력으로 읽어 엉뚱하게 실패한다.
 */
@DisplayName("링크 인증 제거 마이그레이션 — S15P21E106-604")
@ResourceLock("backend-output-capture")
class EmailLinkVerificationRemovalMigrationTest {

    /** 이 마이그레이션 직전 버전. 새 마이그레이션이 뒤에 붙어도 이 값은 그대로다. */
    private static final String VERSION_BEFORE_REMOVAL = "202609181100";

    private static final MigrationPostgresSupport.MigrationTestDatabase DATABASE =
            MigrationPostgresSupport.database("link_verification_removal");

    private static long pendingUserId;
    private static long activeUserId;

    @BeforeAll
    static void migrateAroundRemoval() throws SQLException {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .target(MigrationVersion.fromVersion(VERSION_BEFORE_REMOVAL))
                .load()
                .migrate();

        pendingUserId = insertUser("pending@feelm.test", "미인증계정", "PENDING_VERIFICATION");
        activeUserId = insertUser("active@feelm.test", "활성계정", "ACTIVE");
        execute(
                "INSERT INTO email_verification_tokens (user_id, token_hash, expires_at) VALUES ("
                        + pendingUserId
                        + ", 'pending-verify-hash', now() + interval '24 hours')");
        execute("INSERT INTO notification_settings (user_id) VALUES (" + pendingUserId + ")");
        execute(
                "INSERT INTO password_reset_tokens (user_id, token_hash, expires_at) VALUES ("
                        + pendingUserId
                        + ", 'pending-reset-hash', now() + interval '30 minutes')");
        execute("INSERT INTO notification_settings (user_id) VALUES (" + activeUserId + ")");

        DATABASE.flyway().migrate();
    }

    @AfterAll
    static void dropDatabase() {
        DATABASE.close();
    }

    @Test
    @DisplayName("잔존 미인증 계정과 그 의존 행이 사라지고 활성 계정은 남는다")
    void removesPendingAccountsAndKeepsActive() {
        assertThat(count("SELECT count(*) FROM users WHERE id = " + pendingUserId)).isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM notification_settings WHERE user_id = "
                                        + pendingUserId))
                .isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM password_reset_tokens WHERE user_id = "
                                        + pendingUserId))
                .isZero();

        assertThat(count("SELECT count(*) FROM users WHERE id = " + activeUserId)).isOne();
        assertThat(
                        count(
                                "SELECT count(*) FROM notification_settings WHERE user_id = "
                                        + activeUserId))
                .isOne();
    }

    @Test
    @DisplayName("링크 인증 토큰 테이블이 사라진다")
    void dropsVerificationTokenTable() {
        assertThat(
                        column(
                                "SELECT table_name FROM information_schema.tables"
                                        + " WHERE table_schema = 'public'"))
                .doesNotContain("email_verification_tokens")
                .contains("password_reset_tokens");
    }

    /** 제약을 좁히지 않으면 애플리케이션이 없앤 값이 스키마 쪽에서 되살아난다. */
    @Test
    @DisplayName("계정 상태 CHECK 제약이 ACTIVE·WITHDRAWN만 허용한다")
    void restrictsStatusCheckConstraint() {
        assertThatThrownBy(
                        () ->
                                insertUser(
                                        "again-pending@feelm.test",
                                        "다시미인증",
                                        "PENDING_VERIFICATION"))
                .hasMessageContaining("ck_users_status");

        assertThatCode(() -> insertUser("another-active@feelm.test", "다른활성", "ACTIVE"))
                .doesNotThrowAnyException();
        assertThatCode(() -> insertUser("withdrawn@feelm.test", "탈퇴계정", "WITHDRAWN"))
                .doesNotThrowAnyException();
    }

    // --- helpers ---------------------------------------------------------

    private static long insertUser(String email, String nickname, String status)
            throws SQLException {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery(
                                "INSERT INTO users (email, nickname, birth_date, gender, status)"
                                        + " VALUES ('"
                                        + email
                                        + "', '"
                                        + nickname
                                        + "', DATE '1999-03-14', 'FEMALE', '"
                                        + status
                                        + "') RETURNING id")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(String sql) {
        return Long.parseLong(column(sql).getFirst());
    }

    private static List<String> column(String sql) {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
            return values;
        } catch (SQLException exception) {
            throw new IllegalStateException(sql, exception);
        }
    }
}
