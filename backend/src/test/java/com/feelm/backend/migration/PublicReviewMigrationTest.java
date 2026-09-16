package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.domain.social.repository.PublicReviewQueryRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("공개 한줄평 schema migration")
class PublicReviewMigrationTest {

    private static final String BEFORE_PUBLIC_REVIEW_VERSION = "202609151537";
    private static final MigrationPostgresSupport.MigrationTestDatabase DATABASE =
            MigrationPostgresSupport.database("public_review");

    private static long userId;
    private static long movieId;
    private static long planOtherMovieId;
    private static long watchRecordId;
    private static long ratingId;
    private static long frameId;
    private static LegacySnapshot before;

    @BeforeAll
    static void migrateLegacyFixture() throws SQLException {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .target(BEFORE_PUBLIC_REVIEW_VERSION)
                .load()
                .migrate();

        userId = insertUser("migration-review@feelm.test", "마이그레이션리뷰");
        movieId = insertMovie(1_610_001, "공개 한줄평 마이그레이션 영화");
        planOtherMovieId = insertMovie(1_610_002, "공개 한줄평 인덱스 대조 영화");
        Instant createdAt = Instant.parse("2026-09-15T12:34:56.123456Z");
        watchRecordId = insertWatch(userId, movieId, createdAt);
        ratingId = insertLegacyRating(userId, movieId, watchRecordId, createdAt);
        frameId = insertFrame(ratingId, userId, movieId, createdAt);
        before = snapshot();

        latestFlyway().migrate();
    }

    @AfterAll
    static void dropDatabase() {
        DATABASE.close();
    }

    @Test
    @DisplayName("T31 공개 한줄평 keyset index와 기존 movie index를 함께 유지한다")
    void migrationCreatesPublicReviewKeysetIndex() throws SQLException {
        assertThat(indexDefinition("ix_ratings_movie_id"))
                .contains(
                        "CREATE INDEX ix_ratings_movie_id ON public.ratings USING btree (movie_id)");
        assertThat(indexDefinition("ix_ratings_public_reviews_movie_created_id"))
                .contains("movie_id, created_at DESC, id DESC")
                .contains("comment IS NOT NULL")
                .contains("btrim((comment)::text,")
                .contains("<> ''::text");
    }

    @Test
    @DisplayName("MR !302: 기존 develop 마이그레이션 적용 뒤 공개 한줄평 마이그레이션을 적용한다")
    void publicReviewMigrationFollowsMergedDevelopMigrations() throws SQLException {
        long mergedDevelopRank =
                longValue(
                        "SELECT installed_rank FROM flyway_schema_history"
                                + " WHERE version = '202609162300'");
        long publicReviewRank =
                longValue(
                        "SELECT installed_rank FROM flyway_schema_history"
                                + " WHERE script LIKE '%add_ratings_spoiler_and_public_review_index.sql'");

        assertThat(publicReviewRank).isGreaterThan(mergedDevelopRank);
    }

    @Test
    @DisplayName("T32 실제 API query shape가 planner 강제 없이 신규 partial index를 사용한다")
    void indexSupportsRepresentativeReviewQuery() throws SQLException {
        seedIndexRows();
        String productionSql = PublicReviewQueryRepository.pageSql(true);
        assertThat(productionSql)
                .contains("JOIN users")
                .contains("u.status = 'ACTIVE'")
                .contains("r.visibility = 'PUBLIC'")
                .contains("r.user_id = :viewerId")
                .contains("NOT EXISTS")
                .contains("FROM user_blocks")
                .contains(PublicReviewQueryRepository.NON_BLANK_COMMENT_PREDICATE)
                .contains(":cursorCreatedAt")
                .contains("ORDER BY r.created_at DESC, r.id DESC LIMIT :limit");

        assertThat(explainReviewQuery(productionSql))
                .contains("ix_ratings_public_reviews_movie_created_id")
                .doesNotContain("Seq Scan on ratings");
    }

    @Test
    @DisplayName("T34 기존 평가를 non-spoiler로 backfill하고 원래 값을 보존한다")
    void migrationBackfillsExistingRatingsAsNonSpoiler() throws SQLException {
        assertThat(booleanValue("SELECT is_spoiler FROM ratings WHERE id = " + ratingId)).isFalse();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("T35 spoiler null 저장을 거절한다")
    void migrationRejectsNullSpoiler() {
        assertThatThrownBy(
                        () ->
                                execute(
                                        "UPDATE ratings SET is_spoiler = NULL WHERE id = "
                                                + ratingId))
                .isInstanceOf(SQLException.class)
                .satisfies(
                        error ->
                                assertThat(((SQLException) error).getSQLState())
                                        .isEqualTo("23502"));
    }

    @Test
    @DisplayName("T44 같은 database에 Flyway migrate를 다시 호출해도 변화가 없다")
    void migrationIsStableOnRepeatedFlywayMigrate() throws SQLException {
        LegacySnapshot snapshot = snapshot();
        assertThat(latestFlyway().migrate().migrationsExecuted).isZero();
        assertThat(latestFlyway().validateWithResult().validationSuccessful).isTrue();
        assertThat(snapshot()).isEqualTo(snapshot);
    }

    @Test
    @DisplayName("T46 movie-rating-frame-watch 관계와 기존 식별자를 보존한다")
    void spoilerMigrationKeepsMovieRatingFrameAndWatchRegression() throws SQLException {
        assertThat(longValue("SELECT user_id FROM ratings WHERE id = " + ratingId))
                .isEqualTo(userId);
        assertThat(longValue("SELECT movie_id FROM ratings WHERE id = " + ratingId))
                .isEqualTo(movieId);
        assertThat(longValue("SELECT watch_record_id FROM ratings WHERE id = " + ratingId))
                .isEqualTo(watchRecordId);
        assertThat(longValue("SELECT rating_id FROM frames WHERE id = " + frameId))
                .isEqualTo(ratingId);
        assertThat(longValue("SELECT count(*) FROM watch_records WHERE id = " + watchRecordId))
                .isOne();
        assertThat(longValue("SELECT count(*) FROM frames WHERE id = " + frameId)).isOne();
    }

    private static Flyway latestFlyway() {
        return Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .load();
    }

    private static long insertUser(String email, String nickname) throws SQLException {
        return longValue(
                "INSERT INTO users(email,nickname,birth_date,gender,status) VALUES ('"
                        + email
                        + "','"
                        + nickname
                        + "',DATE '1995-05-05','OTHER','ACTIVE') RETURNING id");
    }

    private static long insertMovie(int tmdbId, String title) throws SQLException {
        return longValue(
                "INSERT INTO movies(tmdb_id,title) VALUES ("
                        + tmdbId
                        + ",'"
                        + title
                        + "') RETURNING id");
    }

    private static long insertWatch(long user, long movie, Instant createdAt) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                INSERT INTO watch_records(
                                    user_id,movie_id,status,clicked_at,watched_at,rated_at
                                ) VALUES (?,?,'RATED',?,?,?) RETURNING id
                                """)) {
            statement.setLong(1, user);
            statement.setLong(2, movie);
            OffsetDateTime time = createdAt.atOffset(ZoneOffset.UTC);
            statement.setObject(3, time);
            statement.setObject(4, time);
            statement.setObject(5, time);
            return returnedId(statement);
        }
    }

    private static long insertLegacyRating(long user, long movie, long watch, Instant createdAt)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                INSERT INTO ratings(
                                    user_id,movie_id,watch_record_id,score,comment,visibility,
                                    created_at,updated_at
                                ) VALUES (?,?,?,4.5,'기존 한줄평','PUBLIC',?,?) RETURNING id
                                """)) {
            statement.setLong(1, user);
            statement.setLong(2, movie);
            statement.setLong(3, watch);
            OffsetDateTime time = createdAt.atOffset(ZoneOffset.UTC);
            statement.setObject(4, time);
            statement.setObject(5, time);
            return returnedId(statement);
        }
    }

    private static long insertFrame(long rating, long user, long movie, Instant createdAt)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO frames(rating_id,user_id,movie_id,created_at)"
                                        + " VALUES (?,?,?,?) RETURNING id")) {
            statement.setLong(1, rating);
            statement.setLong(2, user);
            statement.setLong(3, movie);
            statement.setObject(4, createdAt.atOffset(ZoneOffset.UTC));
            return returnedId(statement);
        }
    }

    private static void seedIndexRows() throws SQLException {
        if (longValue("SELECT count(*) FROM ratings") > 1_000) {
            return;
        }
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            """
                            WITH inserted AS (
                                INSERT INTO users(email,nickname,birth_date,gender,status)
                                SELECT 'review-plan-' || value || '@feelm.test',
                                       '인덱스' || value,
                                       DATE '1995-05-05', 'OTHER', 'ACTIVE'
                                FROM generate_series(1, 12000) value
                                RETURNING id
                            ), numbered AS (
                                SELECT id, row_number() OVER (ORDER BY id) AS ordinal
                                FROM inserted
                            )
                            INSERT INTO ratings(
                                user_id,movie_id,score,comment,visibility,created_at,updated_at
                            )
                            SELECT id,
                                   CASE WHEN ordinal <= 6000 THEN ? ELSE ? END,
                                   3.5,
                                   '인덱스 한줄평 ' || ordinal,
                                   CASE WHEN ordinal % 20 = 0 THEN 'PRIVATE' ELSE 'PUBLIC' END,
                                   TIMESTAMPTZ '2026-09-16 12:00:00+00'
                                       - (ordinal || ' seconds')::interval,
                                   TIMESTAMPTZ '2026-09-16 12:00:00+00'
                                       - (ordinal || ' seconds')::interval
                            FROM numbered
                            """)) {
                statement.setLong(1, movieId);
                statement.setLong(2, planOtherMovieId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            """
                            INSERT INTO user_blocks(blocker_id, blocked_id)
                            SELECT ?, user_id
                            FROM ratings
                            WHERE movie_id = ? AND user_id <> ?
                            ORDER BY id
                            LIMIT 200
                            """)) {
                statement.setLong(1, userId);
                statement.setLong(2, movieId);
                statement.setLong(3, userId);
                statement.executeUpdate();
            }
            connection.commit();
        }
        execute("ANALYZE users");
        execute("ANALYZE ratings");
        execute("ANALYZE user_blocks");
    }

    private static String explainReviewQuery(String productionSql) throws SQLException {
        String jdbcSql =
                productionSql
                        .replace(":movieId", "?")
                        .replace(":viewerId", "?")
                        .replace(":validCursor", "?")
                        .replace(":cursorCreatedAt", "?")
                        .replace(":cursorRatingId", "?")
                        .replace(":limit", "?");
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement("EXPLAIN (COSTS OFF) " + jdbcSql)) {
            statement.setLong(1, userId);
            statement.setLong(2, movieId);
            statement.setBoolean(3, true);
            statement.setLong(4, movieId);
            statement.setLong(5, userId);
            statement.setLong(6, userId);
            statement.setLong(7, userId);
            OffsetDateTime cursorTime =
                    Instant.parse("2026-09-17T00:00:00Z").atOffset(ZoneOffset.UTC);
            statement.setObject(8, cursorTime);
            statement.setObject(9, cursorTime);
            statement.setLong(10, Long.MAX_VALUE);
            statement.setInt(11, 20);
            try (ResultSet result = statement.executeQuery()) {
                List<String> lines = new ArrayList<>();
                while (result.next()) {
                    lines.add(result.getString(1));
                }
                return String.join("\n", lines);
            }
        }
    }

    private static LegacySnapshot snapshot() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT id,user_id,movie_id,watch_record_id,score,comment,visibility,
                                       created_at,updated_at
                                FROM ratings WHERE id = ?
                                """)) {
            statement.setLong(1, ratingId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return new LegacySnapshot(
                        result.getLong("id"),
                        result.getLong("user_id"),
                        result.getLong("movie_id"),
                        result.getLong("watch_record_id"),
                        result.getBigDecimal("score"),
                        result.getString("comment"),
                        result.getString("visibility"),
                        result.getObject("created_at", OffsetDateTime.class).toInstant(),
                        result.getObject("updated_at", OffsetDateTime.class).toInstant());
            }
        }
    }

    private static String indexDefinition(String name) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT indexdef FROM pg_indexes"
                                        + " WHERE schemaname='public' AND indexname=?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static boolean booleanValue(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private static long longValue(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static long returnedId(PreparedStatement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection connect() throws SQLException {
        return DATABASE.connect();
    }

    private record LegacySnapshot(
            long id,
            long userId,
            long movieId,
            long watchRecordId,
            BigDecimal score,
            String comment,
            String visibility,
            Instant createdAt,
            Instant updatedAt) {}
}
