package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("MovieLens 범위의 반별점 마이그레이션")
class RatingHalfStepMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("feelm")
                    .withUsername("feelm")
                    .withPassword("feelm")
                    .withEnv("POSTGRES_INITDB_ARGS", "--locale=C.UTF-8");

    private static final AtomicInteger MOVIE_IDS = new AtomicInteger(980_000);
    private static final List<Long> LEGACY_RATINGS = new ArrayList<>();
    private static long userId;

    @BeforeAll
    static void migrateWithExistingIntegerRatings() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("202609051000")
                .load()
                .migrate();

        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "INSERT INTO users (email, nickname, birth_date, gender, status)"
                                        + " VALUES ('half-step@feelm.test', '반별점검증',"
                                        + " DATE '1999-03-14', 'OTHER', 'ACTIVE') RETURNING id")) {
            result.next();
            userId = result.getLong(1);
        }
        for (int score = 1; score <= 5; score++) {
            LEGACY_RATINGS.add(insertRating(Integer.toString(score)));
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @Test
    @DisplayName("기존 1~5점 평가와 식별자를 그대로 보존한다")
    void preservesExistingRatings() throws SQLException {
        for (int index = 0; index < LEGACY_RATINGS.size(); index++) {
            assertThat(readScore(LEGACY_RATINGS.get(index)))
                    .isEqualByComparingTo(BigDecimal.valueOf(index + 1));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.5", "1", "1.5", "2", "2.5", "3", "3.5", "4", "4.5", "5", "4.50"})
    @DisplayName("반별점 열 단계와 같은 값의 소수 표기를 생성·수정할 수 있다")
    void acceptsEveryHalfStepOnCreateAndUpdate(String score) throws SQLException {
        long ratingId = insertRating(score);
        assertThat(readScore(ratingId)).isEqualByComparingTo(score);
        updateScore(ratingId, "3");
        updateScore(ratingId, score);
        assertThat(readScore(ratingId)).isEqualByComparingTo(score);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.5", "0", "0.49", "5.01", "5.5", "NaN", "Infinity", "-Infinity"})
    @DisplayName("범위 밖 점수와 유한하지 않은 값을 거절한다")
    void rejectsOutOfRangeScores(String score) throws SQLException {
        assertRejectedOnCreateAndUpdate(score, "ck_ratings_score_range");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.51", "1.25", "4.49", "4.51", "4.499999999999999999"})
    @DisplayName("0.5 배수가 아닌 값은 반올림하지 않고 거절한다")
    void rejectsNonHalfStepsWithoutRounding(String score) throws SQLException {
        assertRejectedOnCreateAndUpdate(score, "ck_ratings_score_step");
    }

    @Test
    @DisplayName("점수 필수 제약을 유지한다")
    void rejectsNullScore() {
        assertThatThrownBy(() -> insertRating(null))
                .isInstanceOf(SQLException.class)
                .satisfies(
                        error ->
                                assertThat(((SQLException) error).getSQLState())
                                        .isEqualTo("23502"));
    }

    private static void assertRejectedOnCreateAndUpdate(String score, String constraint)
            throws SQLException {
        assertThatThrownBy(() -> insertRating(score))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(constraint);
        long ratingId = insertRating("3");
        assertThatThrownBy(() -> updateScore(ratingId, score))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(constraint);
        assertThat(readScore(ratingId)).isEqualByComparingTo("3");
    }

    private static long insertRating(String score) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement movie =
                        connection.prepareStatement(
                                "INSERT INTO movies (tmdb_id, title) VALUES (?, ?) RETURNING id");
                PreparedStatement rating =
                        connection.prepareStatement(
                                "INSERT INTO ratings (user_id, movie_id, score)"
                                        + " VALUES (?, ?, CAST(? AS numeric)) RETURNING id")) {
            movie.setInt(1, MOVIE_IDS.incrementAndGet());
            movie.setString(2, "반별점 검증 영화");
            try (ResultSet result = movie.executeQuery()) {
                result.next();
                rating.setLong(2, result.getLong(1));
            }
            rating.setLong(1, userId);
            rating.setString(3, score);
            try (ResultSet result = rating.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static void updateScore(long ratingId, String score) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE ratings SET score = CAST(? AS numeric) WHERE id = ?")) {
            statement.setString(1, score);
            statement.setLong(2, ratingId);
            statement.executeUpdate();
        }
    }

    private static BigDecimal readScore(long ratingId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT score FROM ratings WHERE id = ?")) {
            statement.setLong(1, ratingId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBigDecimal(1);
            }
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
