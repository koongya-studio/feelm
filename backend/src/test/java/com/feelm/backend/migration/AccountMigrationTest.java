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
 * 계정·온보딩 도메인 마이그레이션을 실제 PostgreSQL에 적용해 검증한다. 기본 테스트는 H2를 쓰므로 PostgreSQL 문법인 Flyway SQL이 실행되지 않는다.
 * 실행에 Docker가 필요하다.
 */
@DisplayName("계정·온보딩 도메인 마이그레이션")
class AccountMigrationTest {

    private static final MigrationPostgresSupport.MigrationTestDatabase DATABASE =
            MigrationPostgresSupport.database("account");

    @BeforeAll
    static void migrate() {
        DATABASE.flyway().migrate();
    }

    @AfterAll
    static void dropDatabase() {
        DATABASE.close();
    }

    @Test
    @DisplayName("계정·온보딩 테이블이 모두 생성된다")
    void createsAccountTables() {
        assertThat(
                        column(
                                "SELECT table_name FROM information_schema.tables"
                                        + " WHERE table_schema = 'public'"))
                .contains(
                        "user_auth_providers",
                        "email_verification_tokens",
                        "password_reset_tokens",
                        "notification_settings",
                        "user_blocks",
                        "onboarding_preferences",
                        "user_otts");
    }

    @Test
    @DisplayName("계정에 종속된 데이터는 사용자 삭제와 함께 지워진다")
    void cascadesUserDependentRows() throws SQLException {
        long userId = insertUser("cascade@feelm.test", "연쇄삭제");
        long otherId = insertUser("cascade-other@feelm.test", "연쇄삭제상대");
        long movieId = insertMovie(910_001, "연쇄삭제 영화");
        long ottId = insertOtt("CASCADE_OTT", 910_001);

        execute(
                "INSERT INTO user_auth_providers (user_id, provider, provider_user_id) VALUES ("
                        + userId
                        + ", 'GOOGLE', 'cascade-google-1')");
        execute(
                "INSERT INTO email_verification_tokens (user_id, token_hash, expires_at) VALUES ("
                        + userId
                        + ", 'cascade-verify-hash', now() + interval '24 hours')");
        execute(
                "INSERT INTO password_reset_tokens (user_id, token_hash, expires_at) VALUES ("
                        + userId
                        + ", 'cascade-reset-hash', now() + interval '30 minutes')");
        execute("INSERT INTO notification_settings (user_id) VALUES (" + userId + ")");
        execute(
                "INSERT INTO user_blocks (blocker_id, blocked_id) VALUES ("
                        + userId
                        + ", "
                        + otherId
                        + ")");
        execute(
                "INSERT INTO onboarding_preferences (user_id, movie_id, preference) VALUES ("
                        + userId
                        + ", "
                        + movieId
                        + ", 'LIKE')");
        execute("INSERT INTO user_otts (user_id, ott_id) VALUES (" + userId + ", " + ottId + ")");

        execute("DELETE FROM users WHERE id = " + userId);

        assertThat(dependentRowCount(userId)).isZero();
        // 차단 상대는 남는다. 지워지는 것은 삭제된 사용자에 종속된 행뿐이다.
        assertThat(count("SELECT count(*) FROM users WHERE id = " + otherId)).isEqualTo(1);
    }

    @Test
    @DisplayName("차단 대상이 삭제되면 차단 기록도 함께 지워진다")
    void cascadesBlocksOfDeletedTarget() throws SQLException {
        long blockerId = insertUser("blocker@feelm.test", "차단자");
        long blockedId = insertUser("blocked@feelm.test", "피차단자");
        execute(
                "INSERT INTO user_blocks (blocker_id, blocked_id) VALUES ("
                        + blockerId
                        + ", "
                        + blockedId
                        + ")");

        execute("DELETE FROM users WHERE id = " + blockedId);

        assertThat(count("SELECT count(*) FROM user_blocks WHERE blocker_id = " + blockerId))
                .isZero();
    }

    @Test
    @DisplayName("참조 중인 기준정보는 삭제되지 않는다")
    void keepsReferencedReferenceData() throws SQLException {
        long userId = insertUser("restrict@feelm.test", "기준정보보호");
        long movieId = insertMovie(910_002, "삭제 금지 영화");
        long ottId = insertOtt("RESTRICT_OTT", 910_002);
        execute(
                "INSERT INTO onboarding_preferences (user_id, movie_id, preference) VALUES ("
                        + userId
                        + ", "
                        + movieId
                        + ", 'DISLIKE')");
        execute("INSERT INTO user_otts (user_id, ott_id) VALUES (" + userId + ", " + ottId + ")");

        assertThatThrownBy(() -> execute("DELETE FROM movies WHERE id = " + movieId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_onboarding_preferences_movie_id");
        assertThatThrownBy(() -> execute("DELETE FROM otts WHERE id = " + ottId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_user_otts_ott_id");
    }

    @Test
    @DisplayName("탈퇴 익명화 뒤에는 같은 이메일·닉네임으로 다시 가입할 수 있다")
    void allowsRejoinAfterAnonymization() throws SQLException {
        long userId = insertUser("rejoin@feelm.test", "재가입");

        // D-9 계정 영역 확정: 물리 삭제가 아니라 상태 전이 + 익명화 치환이다(API-013).
        execute(
                "UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now(), password_hash = NULL,"
                        + " email = 'withdrawn-"
                        + userId
                        + "@feelm.invalid', nickname = '탈퇴"
                        + userId
                        + "' WHERE id = "
                        + userId);

        assertThatCode(() -> insertUser("rejoin@feelm.test", "재가입")).doesNotThrowAnyException();
        assertThat(
                        count(
                                "SELECT count(*) FROM users WHERE status = 'WITHDRAWN' AND id = "
                                        + userId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("같은 소셜 계정을 두 사용자에게 연결할 수 없다")
    void rejectsDuplicateSocialAccount() throws SQLException {
        long userId = insertUser("social-a@feelm.test", "소셜에이");
        long otherId = insertUser("social-b@feelm.test", "소셜비");
        execute(
                "INSERT INTO user_auth_providers (user_id, provider, provider_user_id) VALUES ("
                        + userId
                        + ", 'KAKAO', 'kakao-1')");

        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO user_auth_providers (user_id, provider,"
                                                + " provider_user_id) VALUES ("
                                                + otherId
                                                + ", 'KAKAO', 'kakao-1')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_user_auth_providers_provider_provider_user_id");
    }

    @Test
    @DisplayName("한 사용자는 같은 소셜 제공자를 두 번 연결할 수 없다")
    void rejectsDuplicateProviderForUser() throws SQLException {
        long userId = insertUser("social-provider@feelm.test", "소셜제공자중복");
        execute(
                "INSERT INTO user_auth_providers (user_id, provider, provider_user_id) VALUES ("
                        + userId
                        + ", 'KAKAO', 'kakao-provider-1')");

        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO user_auth_providers (user_id, provider,"
                                                + " provider_user_id) VALUES ("
                                                + userId
                                                + ", 'KAKAO', 'kakao-provider-2')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_user_auth_providers_user_id_provider");
    }

    @Test
    @DisplayName("자기 자신은 차단할 수 없다")
    void rejectsSelfBlock() throws SQLException {
        long userId = insertUser("self-block@feelm.test", "자기차단");

        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO user_blocks (blocker_id, blocked_id) VALUES ("
                                                + userId
                                                + ", "
                                                + userId
                                                + ")"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_user_blocks_not_self");
    }

    @Test
    @DisplayName("같은 영화에 대한 온보딩 선호는 사용자별로 1개다")
    void rejectsDuplicateOnboardingPreference() throws SQLException {
        long userId = insertUser("onboarding@feelm.test", "온보딩중복");
        long movieId = insertMovie(910_003, "중복 선택 영화");
        execute(
                "INSERT INTO onboarding_preferences (user_id, movie_id, preference) VALUES ("
                        + userId
                        + ", "
                        + movieId
                        + ", 'LIKE')");

        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO onboarding_preferences (user_id, movie_id,"
                                                + " preference) VALUES ("
                                                + userId
                                                + ", "
                                                + movieId
                                                + ", 'DISLIKE')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_onboarding_preferences_user_id_movie_id");
    }

    @Test
    @DisplayName("알림 설정 기본값이 API-017 처리 규칙과 같다")
    void appliesNotificationDefaultsFromContract() throws SQLException {
        long userId = insertUser("notification@feelm.test", "알림기본값");

        execute("INSERT INTO notification_settings (user_id) VALUES (" + userId + ")");

        assertThat(
                        column(
                                "SELECT rating_enabled || ',' || recommendation_enabled || ',' ||"
                                        + " marketing_enabled FROM notification_settings WHERE"
                                        + " user_id = "
                                        + userId))
                .containsExactly("true,true,false");
    }

    @Test
    @DisplayName("허용되지 않은 소셜 제공자와 선호 값은 저장되지 않는다")
    void rejectsUnknownCodeValues() throws SQLException {
        long userId = insertUser("code-check@feelm.test", "코드검증");
        long movieId = insertMovie(910_004, "코드 검증 영화");

        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO user_auth_providers (user_id, provider,"
                                                + " provider_user_id) VALUES ("
                                                + userId
                                                + ", 'APPLE', 'apple-1')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_user_auth_providers_provider");
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO onboarding_preferences (user_id, movie_id,"
                                                + " preference) VALUES ("
                                                + userId
                                                + ", "
                                                + movieId
                                                + ", 'MAYBE')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_onboarding_preferences_preference");
    }

    // --- helpers ---------------------------------------------------------

    /** 사용자에 종속된 일곱 테이블의 잔여 행 수. 삭제된 사용자에 남는 행이 없어야 한다. */
    private static long dependentRowCount(long userId) {
        return count(
                "SELECT (SELECT count(*) FROM user_auth_providers WHERE user_id = "
                        + userId
                        + ") + (SELECT count(*) FROM email_verification_tokens WHERE user_id = "
                        + userId
                        + ") + (SELECT count(*) FROM password_reset_tokens WHERE user_id = "
                        + userId
                        + ") + (SELECT count(*) FROM notification_settings WHERE user_id = "
                        + userId
                        + ") + (SELECT count(*) FROM user_blocks WHERE blocker_id = "
                        + userId
                        + ") + (SELECT count(*) FROM onboarding_preferences WHERE user_id = "
                        + userId
                        + ") + (SELECT count(*) FROM user_otts WHERE user_id = "
                        + userId
                        + ")");
    }

    private static long insertUser(String email, String nickname) throws SQLException {
        return insertReturningId(
                "INSERT INTO users (email, nickname, birth_date, gender, status) VALUES ('"
                        + email
                        + "', '"
                        + nickname
                        + "', DATE '1999-03-14', 'FEMALE', 'ACTIVE') RETURNING id");
    }

    private static long insertMovie(int tmdbId, String title) throws SQLException {
        return insertReturningId(
                "INSERT INTO movies (tmdb_id, title) VALUES ("
                        + tmdbId
                        + ", '"
                        + title
                        + "') RETURNING id");
    }

    private static long insertOtt(String code, int tmdbProviderId) throws SQLException {
        return insertReturningId(
                "INSERT INTO otts (code, name, tmdb_provider_id) VALUES ('"
                        + code
                        + "', '"
                        + code
                        + "', "
                        + tmdbProviderId
                        + ") RETURNING id");
    }

    private static long insertReturningId(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(String sql) {
        return Long.parseLong(column(sql).getFirst());
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
