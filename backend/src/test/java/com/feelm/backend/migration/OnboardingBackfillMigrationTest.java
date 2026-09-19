package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 온보딩 소급 보정 마이그레이션이 기존 행을 실제로 보정하는지 검증한다(S15P21E106-648).
 *
 * <p>링크 인증 제거 테스트와 같은 이유로 <b>두 번에 나눠</b> 적용한다. 보정 대상이 이 마이그레이션 <i>이전</i>에 온보딩만 마친 행이라, 한 번에 전부 적용하면
 * 보정할 행을 만들 자리가 없다. 직전 버전까지 적용해 온보딩 선택과 기존 평가·감상 기록을 만든 뒤 나머지를 적용한다.
 *
 * <p>실행에 Docker가 필요하다. Flyway가 기동 로그를 표준 출력에 쏟으므로 출력 캡처를 쓰는 테스트와 같은 잠금을 잡는다.
 */
@DisplayName("온보딩 소급 보정 마이그레이션 — S15P21E106-648")
@ResourceLock("backend-output-capture")
class OnboardingBackfillMigrationTest {

    /** 이 마이그레이션 직전 버전. 사이에 새 마이그레이션이 끼면 이 값도 함께 올린다. */
    private static final String VERSION_BEFORE_BACKFILL = "202609191400";

    private static final Path MIGRATION_FILE =
            Path.of(
                    "src/main/resources/db/migration/"
                            + "V202609192100__backfill_onboarding_ratings.sql");

    private static final MigrationPostgresSupport.MigrationTestDatabase DATABASE =
            MigrationPostgresSupport.database("onboarding_backfill");

    private static long onboardedUserId;
    private static long reusedWatchUserId;
    private static long ratedUserId;
    private static long withdrawnUserId;
    private static long mixedUserId;
    private static long otherRaterUserId;
    private static long expiredWatchUserId;

    private static long likedMovieId;
    private static long dislikedMovieId;
    private static long flavorlessMovieId;
    private static long reusedWatchMovieId;
    private static long alreadyRatedMovieId;
    private static long untouchedMovieId;
    private static long mixedMovieId;
    private static long expiredWatchMovieId;

    private static long existingRatingId;
    private static long reusedWatchRecordId;
    private static long expiredWatchRecordId;

    @BeforeAll
    static void migrateAroundBackfill() throws SQLException {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .target(MigrationVersion.fromVersion(VERSION_BEFORE_BACKFILL))
                .load()
                .migrate();

        long flavorId = scalar("SELECT min(id) FROM popcorn_flavors");

        onboardedUserId = insertUser("onboarded@feelm.test", "소급대상", "ACTIVE");
        reusedWatchUserId = insertUser("watching@feelm.test", "감상중", "ACTIVE");
        ratedUserId = insertUser("rated@feelm.test", "이미평가", "ACTIVE");
        withdrawnUserId = insertUser("withdrawn@feelm.test", "탈퇴자", "WITHDRAWN");
        mixedUserId = insertUser("mixed@feelm.test", "혼합집계", "ACTIVE");
        // 온보딩 선택이 없어 보정 대상이 아닌 기존 평가자.
        otherRaterUserId = insertUser("other-rater@feelm.test", "다른평가자", "ACTIVE");
        expiredWatchUserId = insertUser("expired@feelm.test", "만료기록", "ACTIVE");

        likedMovieId = insertMovie(9_001, "좋아요 영화", flavorId);
        dislikedMovieId = insertMovie(9_002, "싫어요 영화", flavorId);
        flavorlessMovieId = insertMovie(9_003, "대표맛 없는 영화", null);
        reusedWatchMovieId = insertMovie(9_004, "보던 영화", flavorId);
        alreadyRatedMovieId = insertMovie(9_005, "이미 평가한 영화", flavorId);
        untouchedMovieId = insertMovie(9_006, "보정과 무관한 영화", flavorId);
        mixedMovieId = insertMovie(9_007, "기존 평가와 보정이 섞인 영화", flavorId);
        expiredWatchMovieId = insertMovie(9_008, "만료된 감상 기록만 있는 영화", flavorId);

        // 보정 대상: LIKE 2건(대표 맛 있음·없음)과 DISLIKE 1건.
        insertPreference(onboardedUserId, likedMovieId, "LIKE");
        insertPreference(onboardedUserId, flavorlessMovieId, "LIKE");
        insertPreference(onboardedUserId, dislikedMovieId, "DISLIKE");

        // 활성 감상 기록이 이미 있는 대상.
        insertPreference(reusedWatchUserId, reusedWatchMovieId, "LIKE");
        reusedWatchRecordId =
                scalar(
                        "INSERT INTO watch_records (user_id, movie_id, status, clicked_at)"
                                + " VALUES ("
                                + reusedWatchUserId
                                + ", "
                                + reusedWatchMovieId
                                + ", 'LINK_CLICKED', TIMESTAMPTZ '2026-09-10 01:02:03+00')"
                                + " RETURNING id");

        // 이미 실제 평가가 있는 대상: 점수·한줄평이 보존되어야 한다.
        insertPreference(ratedUserId, alreadyRatedMovieId, "LIKE");
        existingRatingId =
                scalar(
                        "INSERT INTO ratings (user_id, movie_id, score, comment, visibility,"
                                + " created_at, updated_at) VALUES ("
                                + ratedUserId
                                + ", "
                                + alreadyRatedMovieId
                                + ", 3.5, '기존 한줄평', 'PUBLIC',"
                                + " TIMESTAMPTZ '2026-09-11 04:05:06+00',"
                                + " TIMESTAMPTZ '2026-09-11 04:05:06+00') RETURNING id");

        // 탈퇴 계정의 온보딩 선택은 보정하지 않는다.
        insertPreference(withdrawnUserId, likedMovieId, "LIKE");

        // 기존 평가 5.0이 있는 영화에 보정 4.0이 더해지고, 어긋나 있던 집계가 함께 맞아야 한다.
        insertPreference(mixedUserId, mixedMovieId, "LIKE");
        execute(
                "INSERT INTO ratings (user_id, movie_id, score, created_at, updated_at) VALUES ("
                        + otherRaterUserId
                        + ", "
                        + mixedMovieId
                        + ", 5.0, TIMESTAMPTZ '2026-09-12 07:08:09+00',"
                        + " TIMESTAMPTZ '2026-09-12 07:08:09+00')");
        execute(
                "UPDATE movies SET rating_count = 42, average_rating = 3.33 WHERE id = "
                        + mixedMovieId);

        // 만료된 감상 기록은 활성 기록이 아니다. 재사용하지 않고 새로 만들어야 한다(D-6).
        insertPreference(expiredWatchUserId, expiredWatchMovieId, "LIKE");
        expiredWatchRecordId =
                scalar(
                        "INSERT INTO watch_records (user_id, movie_id, status, clicked_at,"
                                + " expired_at) VALUES ("
                                + expiredWatchUserId
                                + ", "
                                + expiredWatchMovieId
                                + ", 'EXPIRED', TIMESTAMPTZ '2026-09-01 00:00:00+00',"
                                + " TIMESTAMPTZ '2026-09-08 00:00:00+00') RETURNING id");

        // 보정 범위 밖 영화의 어긋난 집계는 건드리지 않는다.
        execute(
                "UPDATE movies SET rating_count = 99, average_rating = 1.11 WHERE id = "
                        + untouchedMovieId);

        DATABASE.flyway().migrate();
    }

    @AfterAll
    static void dropDatabase() {
        DATABASE.close();
    }

    @Test
    @DisplayName("온보딩만 마친 사용자의 선택이 LIKE 4.0·DISLIKE 2.0 평가로 생성된다")
    void createsRatingsForOnboardedUsers() {
        assertThat(
                        column(
                                "SELECT score::text FROM ratings WHERE user_id = "
                                        + onboardedUserId
                                        + " AND movie_id = "
                                        + likedMovieId))
                .containsExactly("4.0");
        assertThat(
                        column(
                                "SELECT score::text FROM ratings WHERE user_id = "
                                        + onboardedUserId
                                        + " AND movie_id = "
                                        + dislikedMovieId))
                .containsExactly("2.0");
        assertThat(
                        count(
                                "SELECT count(*) FROM ratings WHERE user_id = "
                                        + onboardedUserId
                                        + " AND watch_record_id IS NOT NULL"))
                .isEqualTo(3);
        assertThat(
                        count(
                                "SELECT count(*) FROM ratings WHERE user_id = "
                                        + onboardedUserId
                                        + " AND (comment IS NOT NULL OR visibility IS NOT NULL"
                                        + " OR is_spoiler)"))
                .isZero();
    }

    @Test
    @DisplayName("생성된 평가마다 같은 시각의 프레임이 하나씩 생긴다")
    void createsFrameForEachCreatedRating() {
        assertThat(count("SELECT count(*) FROM frames WHERE user_id = " + onboardedUserId))
                .isEqualTo(3);
        assertThat(
                        count(
                                "SELECT count(*) FROM frames f JOIN ratings r ON r.id = f.rating_id"
                                        + " WHERE f.user_id = "
                                        + onboardedUserId
                                        + " AND f.created_at = r.created_at"
                                        + " AND f.movie_id = r.movie_id"))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("활성 감상 기록이 없으면 평가 시각과 같은 RATED 기록을 새로 만든다")
    void createsRatedWatchRecordWhenNoneActive() {
        assertThat(
                        count(
                                "SELECT count(*) FROM watch_records w JOIN ratings r"
                                        + " ON r.watch_record_id = w.id WHERE w.user_id = "
                                        + onboardedUserId
                                        + " AND w.status = 'RATED'"
                                        + " AND w.rated_at = r.created_at"
                                        + " AND w.clicked_at IS NULL AND w.watched_at IS NULL"))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("활성 감상 기록이 있으면 새로 만들지 않고 그 기록을 RATED로 전이시킨다")
    void reusesActiveWatchRecordInsteadOfCreating() {
        assertThat(count("SELECT count(*) FROM watch_records WHERE user_id = " + reusedWatchUserId))
                .isOne();
        assertThat(column("SELECT status FROM watch_records WHERE id = " + reusedWatchRecordId))
                .containsExactly("RATED");
        assertThat(
                        count(
                                "SELECT count(*) FROM ratings WHERE user_id = "
                                        + reusedWatchUserId
                                        + " AND watch_record_id = "
                                        + reusedWatchRecordId
                                        + " AND score = 4.0"))
                .isOne();
        // 클릭 시각은 상태 전이 이력이라 보존한다.
        assertThat(
                        count(
                                "SELECT count(*) FROM watch_records WHERE id = "
                                        + reusedWatchRecordId
                                        + " AND clicked_at = TIMESTAMPTZ '2026-09-10 01:02:03+00'"))
                .isOne();
    }

    @Test
    @DisplayName("이미 실제 평가가 있던 영화는 점수·한줄평·시각이 그대로다")
    void keepsExistingRatingUntouched() {
        assertThat(count("SELECT count(*) FROM ratings WHERE user_id = " + ratedUserId)).isOne();
        // 시각은 문자열로 비교하지 않는다. 렌더링이 JDBC 세션 TimeZone에 따라 달라진다.
        assertThat(
                        count(
                                "SELECT count(*) FROM ratings WHERE id = "
                                        + existingRatingId
                                        + " AND score = 3.5 AND comment = '기존 한줄평'"
                                        + " AND visibility = 'PUBLIC' AND NOT is_spoiler"
                                        + " AND created_at = TIMESTAMPTZ '2026-09-11 04:05:06+00'"
                                        + " AND watch_record_id IS NULL"))
                .isOne();
        // 평가는 있었지만 프레임이 없던 행은 이번 보정의 대상이 아니다.
        assertThat(count("SELECT count(*) FROM frames WHERE user_id = " + ratedUserId)).isZero();
        // 감상 기록도 만들지 않는다.
        assertThat(count("SELECT count(*) FROM watch_records WHERE user_id = " + ratedUserId))
                .isZero();
    }

    @Test
    @DisplayName("탈퇴 계정의 온보딩 선택은 보정하지 않는다")
    void skipsWithdrawnUsers() {
        assertThat(count("SELECT count(*) FROM ratings WHERE user_id = " + withdrawnUserId))
                .isZero();
        assertThat(count("SELECT count(*) FROM frames WHERE user_id = " + withdrawnUserId))
                .isZero();
        assertThat(count("SELECT count(*) FROM watch_records WHERE user_id = " + withdrawnUserId))
                .isZero();
    }

    @Test
    @DisplayName("같은 보정을 다시 실행해도 평가·프레임·감상 기록이 늘지 않는다")
    void doesNotDuplicateWhenReapplied() throws Exception {
        long ratings = count("SELECT count(*) FROM ratings");
        long frames = count("SELECT count(*) FROM frames");
        long watchRecords = count("SELECT count(*) FROM watch_records");

        execute(Files.readString(MIGRATION_FILE, StandardCharsets.UTF_8));

        assertThat(count("SELECT count(*) FROM ratings")).isEqualTo(ratings);
        assertThat(count("SELECT count(*) FROM frames")).isEqualTo(frames);
        assertThat(count("SELECT count(*) FROM watch_records")).isEqualTo(watchRecords);
    }

    @Test
    @DisplayName("보정 대상 영화의 평균 별점·평가 수가 ratings 집계와 일치한다")
    void refreshesMovieRatingAggregate() {
        assertThat(
                        column(
                                "SELECT rating_count || '|' || average_rating FROM movies"
                                        + " WHERE id = "
                                        + likedMovieId))
                .containsExactly("1|4.00");
        // 기존 평가 5.0 + 보정 4.0. 어긋나 있던 42|3.33이 재계산으로 교정된다.
        assertThat(
                        column(
                                "SELECT rating_count || '|' || average_rating FROM movies"
                                        + " WHERE id = "
                                        + mixedMovieId))
                .containsExactly("2|4.50");
        // 보정이 건드린 영화만 대상이다. 보정 범위 밖 영화의 기존 어긋남까지 고치지 않는다.
        assertThat(
                        count(
                                "SELECT count(*) FROM movies m JOIN ("
                                        + " SELECT movie_id, count(*)::int AS c,"
                                        + " round(avg(score), 2) AS a FROM ratings"
                                        + " GROUP BY movie_id) agg ON agg.movie_id = m.id"
                                        + " WHERE m.id IN ("
                                        + affectedMovieIds()
                                        + ") AND (m.rating_count <> agg.c"
                                        + " OR m.average_rating <> agg.a)"))
                .isZero();
    }

    @Test
    @DisplayName("만료된 감상 기록만 있으면 재사용하지 않고 새 RATED 기록을 만든다")
    void createsNewWatchRecordWhenOnlyExpiredExists() {
        assertThat(
                        count(
                                "SELECT count(*) FROM watch_records WHERE user_id = "
                                        + expiredWatchUserId))
                .isEqualTo(2);
        assertThat(
                        count(
                                "SELECT count(*) FROM watch_records WHERE id = "
                                        + expiredWatchRecordId
                                        + " AND status = 'EXPIRED' AND rated_at IS NULL"
                                        + " AND expired_at = TIMESTAMPTZ '2026-09-08 00:00:00+00'"))
                .isOne();
        assertThat(
                        count(
                                "SELECT count(*) FROM ratings r JOIN watch_records w"
                                        + " ON w.id = r.watch_record_id WHERE r.user_id = "
                                        + expiredWatchUserId
                                        + " AND w.id <> "
                                        + expiredWatchRecordId
                                        + " AND w.status = 'RATED'"))
                .isOne();
    }

    @Test
    @DisplayName("보정 대상이 아닌 영화의 집계는 건드리지 않는다")
    void leavesUntouchedMovieAggregateAlone() {
        assertThat(
                        column(
                                "SELECT rating_count || '|' || average_rating FROM movies"
                                        + " WHERE id = "
                                        + untouchedMovieId))
                .containsExactly("99|1.11");
    }

    @Test
    @DisplayName("보정된 사용자의 팝콘이 대표 맛 있는 영화만으로 집계된다")
    void producesPopcornBucketsForBackfilledUser() {
        assertThat(
                        column(
                                "SELECT m.representative_flavor_id || '|' || count(r.id)"
                                        + " FROM ratings r JOIN movies m ON m.id = r.movie_id"
                                        + " WHERE r.user_id = "
                                        + onboardedUserId
                                        + " AND m.representative_flavor_id IS NOT NULL"
                                        + " GROUP BY m.representative_flavor_id"))
                .hasSize(1)
                .allSatisfy(row -> assertThat(row).endsWith("|2"));
    }

    // --- helpers ---------------------------------------------------------

    /** 보정이 평가를 만든 영화. 이 영화들만 집계 재계산 대상이다. */
    private static String affectedMovieIds() {
        return likedMovieId
                + ", "
                + dislikedMovieId
                + ", "
                + flavorlessMovieId
                + ", "
                + reusedWatchMovieId
                + ", "
                + mixedMovieId
                + ", "
                + expiredWatchMovieId;
    }

    private static long insertUser(String email, String nickname, String status) {
        return scalar(
                "INSERT INTO users (email, nickname, birth_date, gender, status) VALUES ('"
                        + email
                        + "', '"
                        + nickname
                        + "', DATE '1999-03-14', 'FEMALE', '"
                        + status
                        + "') RETURNING id");
    }

    private static long insertMovie(int tmdbId, String title, Long flavorId) {
        return scalar(
                "INSERT INTO movies (tmdb_id, title, representative_flavor_id) VALUES ("
                        + tmdbId
                        + ", '"
                        + title
                        + "', "
                        + (flavorId == null ? "NULL" : flavorId)
                        + ") RETURNING id");
    }

    private static void insertPreference(long userId, long movieId, String preference)
            throws SQLException {
        execute(
                "INSERT INTO onboarding_preferences (user_id, movie_id, preference) VALUES ("
                        + userId
                        + ", "
                        + movieId
                        + ", '"
                        + preference
                        + "')");
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long scalar(String sql) {
        return Long.parseLong(column(sql).getFirst());
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
