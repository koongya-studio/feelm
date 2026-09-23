package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;

/** 배포 DB의 ratings 정본과 영화별 비정규화 집계를 다시 일치시키는 보정을 검증한다. */
@DisplayName("영화 평가 집계 복구 마이그레이션 — S15P21E106-636")
@ResourceLock("backend-output-capture")
class MovieRatingAggregateRepairMigrationTest {

    private static final String VERSION_BEFORE_REPAIR = "202609221000";

    private static final Path MIGRATION_FILE =
            Path.of(
                    "src/main/resources/db/migration/"
                            + "V202609221200__repair_movie_rating_aggregates.sql");

    private static final MigrationPostgresSupport.MigrationTestDatabase DATABASE =
            MigrationPostgresSupport.databaseAtVersion(
                    "movie_rating_aggregate", VERSION_BEFORE_REPAIR);

    private static long ratedMovieId;
    private static long emptyMovieId;

    @BeforeAll
    static void migrateAroundRepair() throws SQLException {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .target(MigrationVersion.fromVersion(VERSION_BEFORE_REPAIR))
                .load()
                .migrate();

        long firstUserId = insertUser("aggregate-1@feelm.test", "집계사용자1");
        long secondUserId = insertUser("aggregate-2@feelm.test", "집계사용자2");
        long thirdUserId = insertUser("aggregate-3@feelm.test", "집계사용자3");
        long fourthUserId = insertUser("aggregate-4@feelm.test", "집계사용자4");
        long deletedUserId = insertUser("aggregate-deleted@feelm.test", "삭제사용자");
        long watchOnlyUserId = insertUser("aggregate-watch@feelm.test", "감상사용자");

        ratedMovieId = insertMovie(636_001, "집계 불일치 영화");
        emptyMovieId = insertMovie(636_002, "평가 없는 영화");

        insertRating(firstUserId, ratedMovieId, "4.5", null);

        long ratedWatchId =
                scalar(
                        "INSERT INTO watch_records (user_id, movie_id, status, rated_at) VALUES ("
                                + secondUserId
                                + ", "
                                + ratedMovieId
                                + ", 'RATED', now()) RETURNING id");
        insertRating(secondUserId, ratedMovieId, "4.5", ratedWatchId);

        long framedRatingId = insertRating(thirdUserId, ratedMovieId, "4.5", null);
        execute(
                "INSERT INTO frames (rating_id, user_id, movie_id) VALUES ("
                        + framedRatingId
                        + ", "
                        + thirdUserId
                        + ", "
                        + ratedMovieId
                        + ")");

        insertRating(fourthUserId, ratedMovieId, "4.0", null);
        execute(
                "UPDATE movies SET rating_count = 0, average_rating = 0.00 WHERE id = "
                        + ratedMovieId);

        long deletedRatingId = insertRating(deletedUserId, emptyMovieId, "5.0", null);
        execute("DELETE FROM ratings WHERE id = " + deletedRatingId);
        execute(
                "INSERT INTO watch_records (user_id, movie_id, status, watched_at) VALUES ("
                        + watchOnlyUserId
                        + ", "
                        + emptyMovieId
                        + ", 'WATCHED_UNRATED', now())");
        execute(
                "UPDATE movies SET rating_count = 7, average_rating = 3.21 WHERE id = "
                        + emptyMovieId);

        DATABASE.flyway().migrate();
    }

    @AfterAll
    static void dropDatabase() {
        DATABASE.close();
    }

    @Test
    @DisplayName("프레임·감상 기록과 무관하게 ratings 전체에서 평균과 평가 수를 복구한다")
    void recalculatesStaleAggregateFromCanonicalRatings() {
        assertThat(movieAggregate(ratedMovieId)).isEqualTo("4|4.38");
        assertThat(count("SELECT count(*) FROM ratings WHERE movie_id = " + ratedMovieId))
                .isEqualTo(4);
        assertThat(count("SELECT count(*) FROM frames WHERE movie_id = " + ratedMovieId)).isOne();
        assertThat(
                        count(
                                "SELECT count(*) FROM ratings WHERE movie_id = "
                                        + ratedMovieId
                                        + " AND watch_record_id IS NOT NULL"))
                .isOne();
    }

    @Test
    @DisplayName("삭제된 평가와 감상 기록만 있는 영화는 0건·0.00으로 복구한다")
    void excludesDeletedRatingsAndWatchOnlyRecordsAndClearsEmptyMovie() {
        assertThat(count("SELECT count(*) FROM ratings WHERE movie_id = " + emptyMovieId)).isZero();
        assertThat(count("SELECT count(*) FROM watch_records WHERE movie_id = " + emptyMovieId))
                .isOne();
        assertThat(movieAggregate(emptyMovieId)).isEqualTo("0|0.00");
    }

    @Test
    @DisplayName("보정 SQL을 다시 실행해도 원천 평가와 집계 결과가 변하지 않는다")
    void isIdempotent() throws Exception {
        List<String> ratingsBefore =
                column(
                        "SELECT user_id || '|' || movie_id || '|' || score"
                                + " FROM ratings ORDER BY id");
        List<String> aggregatesBefore =
                column(
                        "SELECT id || '|' || rating_count || '|' || average_rating"
                                + " FROM movies WHERE id IN ("
                                + ratedMovieId
                                + ", "
                                + emptyMovieId
                                + ") ORDER BY id");

        executeInTransaction(Files.readString(MIGRATION_FILE, StandardCharsets.UTF_8));

        assertThat(
                        column(
                                "SELECT user_id || '|' || movie_id || '|' || score"
                                        + " FROM ratings ORDER BY id"))
                .isEqualTo(ratingsBefore);
        assertThat(
                        column(
                                "SELECT id || '|' || rating_count || '|' || average_rating"
                                        + " FROM movies WHERE id IN ("
                                        + ratedMovieId
                                        + ", "
                                        + emptyMovieId
                                        + ") ORDER BY id"))
                .isEqualTo(aggregatesBefore);
    }

    @Test
    @DisplayName("직접 평가 삽입과 잠금 획득이 교차해도 재시도 후 보정을 완료한다")
    void retriesLockAcquisitionWhenDirectInsertOverlaps() throws Exception {
        try (MigrationPostgresSupport.MigrationTestDatabase database =
                MigrationPostgresSupport.databaseAtVersion(
                        "movie_rating_concurrency", VERSION_BEFORE_REPAIR)) {
            long userId =
                    scalar(
                            database,
                            "INSERT INTO users (email, nickname, birth_date, gender, status)"
                                    + " VALUES ('concurrent-direct@feelm.test', '직접삽입',"
                                    + " DATE '1999-03-14', 'FEMALE', 'ACTIVE') RETURNING id");
            long movieId =
                    scalar(
                            database,
                            "INSERT INTO movies (tmdb_id, title)"
                                    + " VALUES (636003, '동시 삽입 영화') RETURNING id");

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try (Connection directWriter = database.connect()) {
                directWriter.setAutoCommit(false);
                try (Statement statement = directWriter.createStatement()) {
                    statement.execute("LOCK TABLE ratings IN ROW EXCLUSIVE MODE");
                }

                Future<?> migration = executor.submit(() -> database.flyway().migrate());
                waitForRepairMigration(database);

                try (Statement statement = directWriter.createStatement()) {
                    statement.execute(
                            "INSERT INTO ratings (user_id, movie_id, score) VALUES ("
                                    + userId
                                    + ", "
                                    + movieId
                                    + ", 4.5)");
                }
                directWriter.commit();
                migration.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }

            assertThat(movieAggregate(database, movieId)).isEqualTo("1|4.50");
        }
    }

    @Test
    @DisplayName("평가 쓰기 경합이 계속되면 제한된 재시도 뒤 명확히 실패한다")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void failsClearlyAfterBoundedRetriesUnderPersistentWriteContention() throws Exception {
        try (MigrationPostgresSupport.MigrationTestDatabase database =
                        MigrationPostgresSupport.databaseAtVersion(
                                "movie_rating_contention", VERSION_BEFORE_REPAIR);
                Connection writer = database.connect()) {
            long movieId =
                    scalar(
                            database,
                            "INSERT INTO movies (tmdb_id, title)"
                                    + " VALUES (636004, '지속 경합 영화') RETURNING id");
            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
                statement.execute("SELECT id FROM movies WHERE id = " + movieId + " FOR UPDATE");
            }

            Throwable failure = catchThrowable(() -> database.flyway().migrate());

            assertThat(failure).isInstanceOf(FlywayException.class);
            SQLException sqlFailure = sqlCause(failure);
            assertThat(sqlFailure.getSQLState()).isEqualTo("55P03");
            assertThat(sqlFailure.getMessage())
                    .contains("Could not acquire movie rating repair locks after 120 attempts")
                    .contains("Pause rating and movie writes, including direct ratings writers");
            assertThat(
                            count(
                                    database,
                                    "SELECT count(*) FROM flyway_schema_history"
                                            + " WHERE version = '202609221200'"))
                    .isZero();

            writer.rollback();
            assertThat(database.flyway().migrate().migrationsExecuted).isPositive();
            assertThat(
                            count(
                                    database,
                                    "SELECT count(*) FROM flyway_schema_history"
                                            + " WHERE version = '202609221200' AND success"))
                    .isOne();
        }
    }

    private static long insertUser(String email, String nickname) {
        return scalar(
                "INSERT INTO users (email, nickname, birth_date, gender, status) VALUES ('"
                        + email
                        + "', '"
                        + nickname
                        + "', DATE '1999-03-14', 'FEMALE', 'ACTIVE') RETURNING id");
    }

    private static long insertMovie(int tmdbId, String title) {
        return scalar(
                "INSERT INTO movies (tmdb_id, title) VALUES ("
                        + tmdbId
                        + ", '"
                        + title
                        + "') RETURNING id");
    }

    private static long insertRating(long userId, long movieId, String score, Long watchRecordId) {
        return scalar(
                "INSERT INTO ratings (user_id, movie_id, watch_record_id, score) VALUES ("
                        + userId
                        + ", "
                        + movieId
                        + ", "
                        + (watchRecordId == null ? "NULL" : watchRecordId)
                        + ", "
                        + score
                        + ") RETURNING id");
    }

    private static String movieAggregate(long movieId) {
        return movieAggregate(DATABASE, movieId);
    }

    private static String movieAggregate(
            MigrationPostgresSupport.MigrationTestDatabase database, long movieId) {
        return column(
                        database,
                        "SELECT rating_count || '|' || average_rating FROM movies WHERE id = "
                                + movieId)
                .getFirst();
    }

    private static void waitForRepairMigration(
            MigrationPostgresSupport.MigrationTestDatabase database) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long retrying = 0;
        while (System.nanoTime() < deadline) {
            retrying =
                    count(
                            database,
                            "SELECT count(*) FROM pg_stat_activity"
                                    + " WHERE datname = current_database()"
                                    + " AND pid <> pg_backend_pid() AND state = 'active'"
                                    + " AND wait_event_type = 'Timeout'"
                                    + " AND wait_event = 'PgSleep'"
                                    + " AND query ILIKE '%LOCK TABLE ratings IN SHARE MODE%'");
            if (retrying > 0) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(retrying).as("첫 NOWAIT 실패 뒤 재시도 대기 중인 마이그레이션 수").isPositive();
    }

    private static SQLException sqlCause(Throwable failure) {
        Throwable cause = failure;
        while (cause != null && !(cause instanceof SQLException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("Flyway 실패의 SQL 원인").isInstanceOf(SQLException.class);
        return (SQLException) cause;
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void executeInTransaction(String sql) throws SQLException {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute(sql);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static long scalar(String sql) {
        return scalar(DATABASE, sql);
    }

    private static long scalar(
            MigrationPostgresSupport.MigrationTestDatabase database, String sql) {
        return Long.parseLong(column(database, sql).getFirst());
    }

    private static long count(String sql) {
        return count(DATABASE, sql);
    }

    private static long count(MigrationPostgresSupport.MigrationTestDatabase database, String sql) {
        return Long.parseLong(column(database, sql).getFirst());
    }

    private static List<String> column(String sql) {
        return column(DATABASE, sql);
    }

    private static List<String> column(
            MigrationPostgresSupport.MigrationTestDatabase database, String sql) {
        try (Connection connection = database.connect();
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
