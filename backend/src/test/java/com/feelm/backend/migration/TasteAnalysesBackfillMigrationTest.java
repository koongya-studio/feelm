package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 취향 분석 소급 백필 마이그레이션 검증(S15P21E106-775, S15P21E106-883).
 *
 * <p>보정 대상이 이 마이그레이션 <i>이전</i>부터 평가가 있던 사용자라, 직전 버전까지 적용해 평가를 만든 뒤 나머지를 적용한다.
 *
 * <p>실행에 Docker가 필요하다. Flyway가 기동 로그를 표준 출력에 쏟으므로 출력 캡처를 쓰는 테스트와 같은 잠금을 잡는다.
 */
@DisplayName("취향 분석 소급 백필 마이그레이션 — S15P21E106-775")
@ResourceLock("backend-output-capture")
class TasteAnalysesBackfillMigrationTest {

    /** 개발 DB에 먼저 적용된 버전. 이 상태에서 백필이 순서 오류 없이 이어서 적용되어야 한다. */
    private static final String VERSION_BEFORE_BACKFILL = "202609241438";

    private static final String BACKFILL_VERSION = "202609250144";

    private static final Path MIGRATION_FILE =
            Path.of(
                    "src/main/resources/db/migration/"
                            + "V202609250144__backfill_taste_analyses.sql");

    private static final MigrationPostgresSupport.MigrationTestDatabase DATABASE =
            MigrationPostgresSupport.database("taste_analyses_backfill");

    private static long ratedUserId;
    private static long alreadyCachedUserId;
    private static long ratinglessUserId;

    @BeforeAll
    static void migrateAroundBackfill() throws SQLException {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .target(MigrationVersion.fromVersion(VERSION_BEFORE_BACKFILL))
                .load()
                .migrate();

        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement()) {
            ratedUserId = insertUser(statement, "rated-775@feelm.test", "평가자");
            alreadyCachedUserId = insertUser(statement, "cached-775@feelm.test", "이미캐시");
            ratinglessUserId = insertUser(statement, "empty-775@feelm.test", "평가없음");

            long genreId =
                    scalar(
                            statement,
                            "INSERT INTO genres (code, name, tmdb_genre_id)"
                                    + " VALUES ('THRILLER775', '스릴러', 977501) RETURNING id");
            long personId =
                    scalar(
                            statement,
                            "INSERT INTO people (tmdb_person_id, name)"
                                    + " VALUES (977502, '봉준호') RETURNING id");
            long oldMovieId = insertMovie(statement, 977503, "살인의 추억", "2003-04-25");
            long newMovieId = insertMovie(statement, 977504, "기생충", "2019-05-30");
            long undatedMovieId = insertMovie(statement, 977505, "개봉일 없음", null);

            for (long movieId : new long[] {oldMovieId, newMovieId, undatedMovieId}) {
                statement.executeUpdate(
                        "INSERT INTO movie_genres (movie_id, genre_id) VALUES ("
                                + movieId
                                + ", "
                                + genreId
                                + ")");
                statement.executeUpdate(
                        "INSERT INTO movie_countries (movie_id, country_code) VALUES ("
                                + movieId
                                + ", 'KR')");
                statement.executeUpdate(
                        "INSERT INTO movie_people (movie_id, person_id, role) VALUES ("
                                + movieId
                                + ", "
                                + personId
                                + ", 'DIRECTOR')");
                insertRating(statement, ratedUserId, movieId, "4.0");
            }

            // 이미 캐시가 있는 사용자는 건드리지 않아야 한다.
            insertRating(statement, alreadyCachedUserId, newMovieId, "2.0");
            statement.executeUpdate(
                    "INSERT INTO taste_analyses (user_id, analysis, keywords, rating_count,"
                            + " analyzed_at) VALUES ("
                            + alreadyCachedUserId
                            + ", '{\"genres\":[],\"countries\":[],\"eras\":[],\"directors\":[]}',"
                            + " '[]', 99, TIMESTAMPTZ '2026-01-01 00:00:00+00')");
        }

        applyBackfill();
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    @Test
    @DisplayName("평가가 있던 사용자에게 4축이 채워진 캐시 행이 생긴다")
    void createsCacheForExistingRaters() throws SQLException {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement()) {
            assertThat(scalar(statement, ratingCountQuery(ratedUserId))).isEqualTo(3L);
            assertThat(text(statement, jsonQuery(ratedUserId, "genres")))
                    .contains("THRILLER775")
                    .contains("스릴러");
            assertThat(text(statement, jsonQuery(ratedUserId, "directors"))).contains("봉준호");
            // 연대 버킷은 10년 단위 2010s 형식이고 합치지 않는다. 개봉일 없는 영화는 버킷에 없다.
            String eras = text(statement, jsonQuery(ratedUserId, "eras"));
            assertThat(eras).contains("2000s").contains("2010s");
            assertThat(countElements(statement, ratedUserId, "eras")).isEqualTo(2L);
            // 비중의 분모는 평가 편수다. 장르 3편 / 평가 3편 = 1.00.
            assertThat(text(statement, jsonQuery(ratedUserId, "genres"))).contains("1.00");
        }
    }

    @Test
    @DisplayName("키워드는 비워 두어 다음 평가 변경 때 채워지게 한다")
    void leavesKeywordsEmpty() throws SQLException {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement()) {
            assertThat(
                            text(
                                    statement,
                                    "SELECT keywords::text FROM taste_analyses WHERE user_id = "
                                            + ratedUserId))
                    .isEqualTo("[]");
        }
    }

    @Test
    @DisplayName("이미 캐시가 있던 사용자는 건드리지 않는다")
    void keepsExistingCacheUntouched() throws SQLException {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement()) {
            assertThat(scalar(statement, ratingCountQuery(alreadyCachedUserId))).isEqualTo(99L);
        }
    }

    @Test
    @DisplayName("평가가 없는 사용자에게는 행을 만들지 않는다")
    void skipsUsersWithoutRatings() throws SQLException {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement()) {
            assertThat(
                            scalar(
                                    statement,
                                    "SELECT count(*) FROM taste_analyses WHERE user_id = "
                                            + ratinglessUserId))
                    .isZero();
        }
    }

    @Test
    @DisplayName("두 번 실행해도 결과가 같다")
    void isIdempotent() throws SQLException {
        long before;
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement()) {
            before = scalar(statement, "SELECT count(*) FROM taste_analyses");
            statement.executeUpdate(readMigration());
        }
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement()) {
            assertThat(scalar(statement, "SELECT count(*) FROM taste_analyses")).isEqualTo(before);
            assertThat(scalar(statement, ratingCountQuery(alreadyCachedUserId))).isEqualTo(99L);
        }
    }

    @Test
    @DisplayName("이미 적용된 최신 버전 다음 순번으로 백필이 기록된다")
    void recordsBackfillAfterPreviouslyAppliedMigration() throws SQLException {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement()) {
            long previousRank =
                    scalar(
                            statement,
                            "SELECT installed_rank FROM flyway_schema_history"
                                    + " WHERE version = '"
                                    + VERSION_BEFORE_BACKFILL
                                    + "'");
            long backfillRank =
                    scalar(
                            statement,
                            "SELECT installed_rank FROM flyway_schema_history"
                                    + " WHERE version = '"
                                    + BACKFILL_VERSION
                                    + "'");

            assertThat(previousRank).isPositive();
            assertThat(backfillRank).isGreaterThan(previousRank);
        }
    }

    // --- 도우미 -----------------------------------------------------------

    private static void applyBackfill() {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .target(MigrationVersion.fromVersion(BACKFILL_VERSION))
                .load()
                .migrate();
    }

    private static String readMigration() {
        try {
            return Files.readString(MIGRATION_FILE, StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("마이그레이션 파일을 읽지 못했습니다.", exception);
        }
    }

    private static String ratingCountQuery(long userId) {
        return "SELECT rating_count FROM taste_analyses WHERE user_id = " + userId;
    }

    private static String jsonQuery(long userId, String key) {
        return "SELECT analysis -> '" + key + "' FROM taste_analyses WHERE user_id = " + userId;
    }

    private static long countElements(Statement statement, long userId, String key)
            throws SQLException {
        return scalar(
                statement,
                "SELECT jsonb_array_length(analysis -> '"
                        + key
                        + "') FROM taste_analyses WHERE user_id = "
                        + userId);
    }

    private static long insertUser(Statement statement, String email, String nickname)
            throws SQLException {
        return scalar(
                statement,
                "INSERT INTO users (email, nickname, birth_date, gender, status) VALUES ('"
                        + email
                        + "', '"
                        + nickname
                        + "', DATE '1996-01-01', 'OTHER', 'ACTIVE') RETURNING id");
    }

    private static long insertMovie(Statement statement, int tmdbId, String title, String released)
            throws SQLException {
        return scalar(
                statement,
                "INSERT INTO movies (tmdb_id, title, release_date) VALUES ("
                        + tmdbId
                        + ", '"
                        + title
                        + "', "
                        + (released == null ? "NULL" : "DATE '" + released + "'")
                        + ") RETURNING id");
    }

    private static void insertRating(Statement statement, long userId, long movieId, String score)
            throws SQLException {
        statement.executeUpdate(
                "INSERT INTO ratings (user_id, movie_id, score) VALUES ("
                        + userId
                        + ", "
                        + movieId
                        + ", "
                        + score
                        + ")");
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    private static String text(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
