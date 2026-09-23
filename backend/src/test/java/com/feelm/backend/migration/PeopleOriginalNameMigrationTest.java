package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * 인물 원어명 컬럼과 그 초기 적재를 검증한다.
 *
 * <p>적재 대상이 이 마이그레이션 <i>이전</i>부터 있던 인물이라 두 번에 나눠 적용한다. 직전 버전까지 적용해 인물을 만든 뒤 나머지를 적용한다.
 *
 * <p>실행에 Docker가 필요하다.
 */
@DisplayName("인물 원어명 적재 마이그레이션")
@ResourceLock("backend-output-capture")
class PeopleOriginalNameMigrationTest {

    /** 이 마이그레이션 직전 버전. 사이에 새 마이그레이션이 끼면 이 값도 함께 올린다. */
    private static final String VERSION_BEFORE = "202609221000";

    /** 컬럼만 추가하는 버전. 적재 직전 상태를 만들기 위해 여기서 한 번 멈춘다. */
    private static final String VERSION_COLUMN_ADDED = "202609221500";

    /** 덤프에서 뽑은 적재 대상. 한국어 표기와 라틴 원어명이 다르다. */
    private static final int TMDB_MARK_HAMILL = 2;

    /**
     * 송강호. 실제 TMDb id이며 원어명도 '송강호'라 적재 목록에서 제외되었다. 가공 id를 쓰면 목록에 한국 인물이 섞여 들어와도 이 단언이 통과하므로 실재하는 제외
     * 대상을 쓴다.
     */
    private static final int TMDB_SONG_KANG_HO = 20_738;

    /** 봉준호. 위와 같은 이유로 제외되었다. */
    private static final int TMDB_BONG_JOON_HO = 21_684;

    /**
     * 이창동. TMDb에 로마자 원어명 'Lee Chang-dong'이 있지만 한국 인물이므로 적재하지 않는다. 시안이 봉준호를 한글 한 줄로 두므로 로마자가 있다고
     * 영문으로 바꾸면 규칙이 어긋난다.
     */
    private static final int TMDB_LEE_CHANG_DONG = 96_657;

    /** 준회. 한 토큰 로마자 이름이라 과거 이름 정규식에서 빠졌던 실제 한국 배우 반례다. 검토된 TMDb person ID·원어명 쌍이 이 값을 제외해야 한다. */
    private static final int TMDB_JU_NE = 2_027_339;

    /** 우승혜. 중국 배우지만 Yu라는 성 때문에 과거 이름 정규식이 한국인으로 잘못 분류했다. 한국인 ID 데이터에 없으므로 라틴 원어명을 채워야 한다. */
    private static final int TMDB_YU_CHENGHUI = 64_707;

    /** 트란 안 홍. 베트남어 확장 라틴 문자(ầ)가 포함된 실제 감독 반례다. */
    private static final int TMDB_TRAN_ANH_HUNG = 107_730;

    /** 마크 해밀과 같은 이름을 미리 넣어 둔 인물. 적재가 기존 값을 덮어쓰지 않아야 한다. */
    private static final int TMDB_PRESET = 3;

    /** 승인 후 현재 name이 원어명과 같아진 기존 READY 리포트 보정 반례. */
    private static final int TMDB_CURRENT_NAME_ALREADY_LATIN = 4;

    private static final MigrationPostgresSupport.MigrationTestDatabase DATABASE =
            MigrationPostgresSupport.databaseAtVersion("people_original_name", VERSION_BEFORE);

    @BeforeAll
    static void migrateAroundBackfill() throws SQLException {
        // 1단계: 컬럼이 생기기 전부터 있던 인물. original_name 없이 넣는다.
        execute(
                "INSERT INTO people (tmdb_person_id, name) VALUES ("
                        + TMDB_MARK_HAMILL
                        + ", '마크 해밀')");
        execute(
                "INSERT INTO people (tmdb_person_id, name) VALUES ("
                        + TMDB_SONG_KANG_HO
                        + ", '송강호')");
        execute(
                "INSERT INTO people (tmdb_person_id, name) VALUES ("
                        + TMDB_BONG_JOON_HO
                        + ", '봉준호')");
        execute(
                "INSERT INTO people (tmdb_person_id, name) VALUES ("
                        + TMDB_LEE_CHANG_DONG
                        + ", '이창동')");
        execute("INSERT INTO people (tmdb_person_id, name) VALUES (" + TMDB_JU_NE + ", '준회')");
        execute(
                "INSERT INTO people (tmdb_person_id, name) VALUES ("
                        + TMDB_YU_CHENGHUI
                        + ", '우승혜')");
        execute(
                "INSERT INTO people (tmdb_person_id, name) VALUES ("
                        + TMDB_TRAN_ANH_HUNG
                        + ", '트란 안 홍')");
        execute("INSERT INTO people (tmdb_person_id, name) VALUES (" + TMDB_PRESET + ", '해리슨 포드')");
        execute(
                "INSERT INTO people (tmdb_person_id, name) VALUES ("
                        + TMDB_CURRENT_NAME_ALREADY_LATIN
                        + ", 'Carrie Fisher')");

        // 2단계: 컬럼 추가까지만 적용한 뒤, 이미 값이 있는 행을 만든다. 적재가 이 값을
        // 덮어쓰지 않아야 수동 교정이 살아남는다.
        migrateTo(VERSION_COLUMN_ADDED);
        execute(
                "UPDATE people SET original_name = '손으로 고친 값' WHERE tmdb_person_id = "
                        + TMDB_PRESET);

        // 3단계: 나머지(적재) 적용.
        DATABASE.flyway().migrate();
    }

    private static void migrateTo(String version) {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    @Test
    @DisplayName("외국 인물의 라틴 원어명만 채우고 한국 인물은 비운다")
    void backfillsLatinOriginalNameOnly() {
        assertThat(originalName(TMDB_MARK_HAMILL)).isEqualTo("Mark Hamill");
        // 원어명도 한글인 인물은 영문 표기가 없으므로 NULL로 남아 카드가 한 줄이 된다.
        assertThat(originalName(TMDB_SONG_KANG_HO)).isNull();
        assertThat(originalName(TMDB_BONG_JOON_HO)).isNull();
        // TMDb에 로마자가 있어도 한국 인물이면 적재하지 않는다.
        assertThat(originalName(TMDB_LEE_CHANG_DONG)).isNull();
        assertThat(originalName(TMDB_JU_NE)).isNull();
        // 이름 모양이 한국식처럼 보여도 명시적인 한국인 데이터에 없으면 외국인 원어명을 쓴다.
        assertThat(originalName(TMDB_YU_CHENGHUI)).isEqualTo("Yu Chenghui");
        assertThat(originalName(TMDB_TRAN_ANH_HUNG)).isEqualTo("Trần Anh Hùng");
        assertThat(originalName(TMDB_CURRENT_NAME_ALREADY_LATIN)).isEqualTo("Carrie Fisher");
    }

    @Test
    @DisplayName("이미 원어명이 있는 행은 덮어쓰지 않는다")
    void keepsExistingOriginalName() {
        assertThat(originalName(TMDB_PRESET)).isEqualTo("손으로 고친 값");
    }

    @Test
    @DisplayName("적재 목록이 기존 인물 이름을 바꾸지 않는다")
    void keepsStoredKoreanNames() {
        assertThat(name(TMDB_MARK_HAMILL)).isEqualTo("마크 해밀");
        assertThat(name(TMDB_SONG_KANG_HO)).isEqualTo("송강호");
        assertThat(name(TMDB_BONG_JOON_HO)).isEqualTo("봉준호");
        assertThat(name(TMDB_LEE_CHANG_DONG)).isEqualTo("이창동");
        assertThat(name(TMDB_JU_NE)).isEqualTo("준회");
        assertThat(name(TMDB_YU_CHENGHUI)).isEqualTo("우승혜");
        assertThat(name(TMDB_TRAN_ANH_HUNG)).isEqualTo("트란 안 홍");
        assertThat(name(TMDB_CURRENT_NAME_ALREADY_LATIN)).isEqualTo("Carrie Fisher");
    }

    @AfterAll
    static void close() {
        DATABASE.close();
    }

    private static String originalName(int tmdbPersonId) {
        return column("SELECT original_name FROM people WHERE tmdb_person_id = " + tmdbPersonId);
    }

    private static String name(int tmdbPersonId) {
        return column("SELECT name FROM people WHERE tmdb_person_id = " + tmdbPersonId);
    }

    private static String column(String sql) {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
