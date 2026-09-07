package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** API·ERD·물리 스키마 규칙에서 확정된 콘텐츠·감상 계약을 실제 PostgreSQL에 적용해 검증한다. */
@Testcontainers
@DisplayName("콘텐츠·감상 도메인 마이그레이션")
class ContentWatchMigrationTest {

    /** 인프라 협의안(S15P21E106-177)과 동일한 이미지. */
    private static final DockerImageName IMAGE =
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(IMAGE)
                    .withDatabaseName("feelm")
                    .withUsername("feelm")
                    .withPassword("feelm")
                    .withEnv("POSTGRES_INITDB_ARGS", "--locale=C.UTF-8");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @Test
    @DisplayName("콘텐츠 관계·감상·평가 테이블이 생성된다")
    void createsContentAndWatchTables() {
        assertThat(
                        column(
                                "SELECT table_name FROM information_schema.tables"
                                        + " WHERE table_schema = 'public'"))
                .contains(
                        "movie_genres",
                        "movie_countries",
                        "movie_people",
                        "movie_ott_providers",
                        "watch_records",
                        "ratings");
    }

    @Test
    @DisplayName("콘텐츠 관계는 ERD의 복합키와 역할 값만 허용한다")
    void enforcesContentRelationKeysAndRole() throws SQLException {
        long movieId = insertMovie(920_001, "관계 검증 영화");
        long genreId = insertGenre("RELATION_GENRE", 920_001);
        long personId = insertPerson(920_001, "관계 검증 인물");
        long ottId = insertOtt("RELATION_OTT", 920_001);

        execute(
                "INSERT INTO movie_genres (movie_id, genre_id) VALUES ("
                        + movieId
                        + ", "
                        + genreId
                        + ")");
        execute(
                "INSERT INTO movie_countries (movie_id, country_code) VALUES ("
                        + movieId
                        + ", 'KR')");
        execute(
                "INSERT INTO movie_people"
                        + " (movie_id, person_id, role, character_name, display_order) VALUES ("
                        + movieId
                        + ", "
                        + personId
                        + ", 'ACTOR', '주연', 1)");
        execute(
                "INSERT INTO movie_ott_providers (movie_id, ott_id, watch_url, synced_at) VALUES ("
                        + movieId
                        + ", "
                        + ottId
                        + ", 'https://example.com/watch', now())");

        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO movie_genres (movie_id, genre_id) VALUES ("
                                                + movieId
                                                + ", "
                                                + genreId
                                                + ")"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("movie_genres_pkey");
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO movie_people"
                                                + " (movie_id, person_id, role, character_name,"
                                                + " display_order) VALUES ("
                                                + movieId
                                                + ", "
                                                + personId
                                                + ", 'WRITER', '작가', 2)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_movie_people_role");

        long invalidCountryMovieId = insertMovie(920_011, "국가 코드 검증 영화");
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO movie_countries (movie_id, country_code)"
                                                + " VALUES ("
                                                + invalidCountryMovieId
                                                + ", 'kr')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_movie_countries_country_code");
    }

    @Test
    @DisplayName("사용자·영화별 활성 감상 기록은 하나이고 만료 기록은 다시 쌓을 수 있다")
    void keepsOneActiveWatchRecordPerUserAndMovie() throws SQLException {
        long userId = insertUser("watch-active@feelm.test", "활성감상");
        long movieId = insertMovie(920_002, "활성 감상 영화");

        execute(
                "INSERT INTO watch_records (user_id, movie_id, status) VALUES ("
                        + userId
                        + ", "
                        + movieId
                        + ", 'LINK_CLICKED')");

        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO watch_records (user_id, movie_id, status)"
                                                + " VALUES ("
                                                + userId
                                                + ", "
                                                + movieId
                                                + ", 'RATED')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uix_watch_records_active_user_movie");

        long expiredUserId = insertUser("watch-expired@feelm.test", "만료감상");
        execute(
                "INSERT INTO watch_records (user_id, movie_id, status) VALUES ("
                        + expiredUserId
                        + ", "
                        + movieId
                        + ", 'EXPIRED'), ("
                        + expiredUserId
                        + ", "
                        + movieId
                        + ", 'EXPIRED')");

        execute(
                "INSERT INTO watch_records (user_id, movie_id, status) VALUES ("
                        + expiredUserId
                        + ", "
                        + movieId
                        + ", 'LINK_CLICKED')");

        assertThat(
                        count(
                                "SELECT count(*) FROM watch_records WHERE user_id = "
                                        + expiredUserId
                                        + " AND movie_id = "
                                        + movieId))
                .isEqualTo(3);

        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO watch_records (user_id, movie_id, status)"
                                                + " VALUES ("
                                                + expiredUserId
                                                + ", "
                                                + movieId
                                                + ", 'RATED')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uix_watch_records_active_user_movie");
    }

    @Test
    @DisplayName("감상 상태와 평가 값은 계약에 정의된 값만 허용한다")
    void rejectsUnknownWatchAndRatingValues() throws SQLException {
        long userId = insertUser("watch-code@feelm.test", "상태검증");
        long movieId = insertMovie(920_003, "상태 검증 영화");

        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO watch_records (user_id, movie_id, status)"
                                                + " VALUES ("
                                                + userId
                                                + ", "
                                                + movieId
                                                + ", 'UNKNOWN')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_watch_records_status");
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO ratings"
                                                + " (user_id, movie_id, score, visibility) VALUES ("
                                                + userId
                                                + ", "
                                                + movieId
                                                + ", 0, 'PRIVATE')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_ratings_score_range");
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO ratings"
                                                + " (user_id, movie_id, score, visibility) VALUES ("
                                                + userId
                                                + ", "
                                                + movieId
                                                + ", 5, 'FRIENDS')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_ratings_visibility");
    }

    @Test
    @DisplayName("평가는 감상 기록 없이 저장할 수 있고 사용자·영화별 하나다")
    void allowsRatingWithoutWatchRecordAndRejectsDuplicateRating() throws SQLException {
        long userId = insertUser("rating@feelm.test", "평가검증");
        long movieId = insertMovie(920_004, "평가 검증 영화");

        long ratingId =
                insertReturningId(
                        "INSERT INTO ratings (user_id, movie_id, score) VALUES ("
                                + userId
                                + ", "
                                + movieId
                                + ", 5) RETURNING id");

        assertThat(
                        count(
                                "SELECT count(*) FROM ratings WHERE id = "
                                        + ratingId
                                        + " AND watch_record_id IS NULL AND visibility IS NULL"))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO ratings"
                                                + " (user_id, movie_id, score, visibility) VALUES ("
                                                + userId
                                                + ", "
                                                + movieId
                                                + ", 4, 'PRIVATE')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_ratings_user_id_movie_id");
    }

    @Test
    @DisplayName("한줄평이 있으면 공개 범위가 필요하고 한줄평이 없으면 생략할 수 있다")
    void requiresVisibilityOnlyWhenCommentExists() throws SQLException {
        long userId = insertUser("rating-visibility@feelm.test", "공개범위검증");
        long movieId = insertMovie(920_012, "공개 범위 검증 영화");

        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO ratings (user_id, movie_id, score, comment)"
                                                + " VALUES ("
                                                + userId
                                                + ", "
                                                + movieId
                                                + ", 5, '한줄평')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_ratings_comment_requires_visibility");

        execute(
                "INSERT INTO ratings (user_id, movie_id, score) VALUES ("
                        + userId
                        + ", "
                        + movieId
                        + ", 5)");

        assertThat(
                        count(
                                "SELECT count(*) FROM ratings WHERE user_id = "
                                        + userId
                                        + " AND movie_id = "
                                        + movieId
                                        + " AND comment IS NULL AND visibility IS NULL"))
                .isEqualTo(1);

        long publicCommentUserId = insertUser("rating-public@feelm.test", "공개한줄평");
        execute(
                "INSERT INTO ratings (user_id, movie_id, score, comment, visibility) VALUES ("
                        + publicCommentUserId
                        + ", "
                        + movieId
                        + ", 4, '공개 한줄평', 'PUBLIC')");

        assertThat(
                        count(
                                "SELECT count(*) FROM ratings WHERE user_id = "
                                        + publicCommentUserId
                                        + " AND movie_id = "
                                        + movieId
                                        + " AND comment = '공개 한줄평'"
                                        + " AND visibility = 'PUBLIC'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("감상 시각과 평가 공개 범위에는 근거 없는 필수값·기본값이 없다")
    void keepsUnconfirmedColumnsNullableWithoutVisibilityDefault() {
        assertThat(
                        column(
                                "SELECT column_name FROM information_schema.columns"
                                        + " WHERE table_schema = 'public'"
                                        + " AND table_name = 'watch_records'"
                                        + " AND is_nullable = 'YES'"
                                        + " AND column_name IN ('clicked_at', 'confirm_due_at',"
                                        + " 'expires_at', 'watched_at', 'rated_at', 'expired_at')"
                                        + " ORDER BY column_name"))
                .containsExactly(
                        "clicked_at",
                        "confirm_due_at",
                        "expired_at",
                        "expires_at",
                        "rated_at",
                        "watched_at");
        assertThat(
                        column(
                                "SELECT (is_nullable = 'YES' AND column_default IS NULL)::text"
                                        + " FROM information_schema.columns"
                                        + " WHERE table_schema = 'public' AND table_name = 'ratings'"
                                        + " AND column_name = 'visibility'"))
                .containsExactly("true");
    }

    @Test
    @DisplayName("콘텐츠 적재 부가 컬럼에는 근거 없는 필수 제약이 없다")
    void keepsContentIngestionDetailsNullable() {
        assertThat(
                        column(
                                "SELECT table_name || '.' || column_name"
                                        + " FROM information_schema.columns"
                                        + " WHERE table_schema = 'public' AND is_nullable = 'YES'"
                                        + " AND ((table_name = 'movie_people'"
                                        + " AND column_name IN ('character_name', 'display_order'))"
                                        + " OR (table_name = 'movie_ott_providers'"
                                        + " AND column_name IN ('watch_url', 'synced_at')))"
                                        + " ORDER BY table_name, column_name"))
                .containsExactly(
                        "movie_ott_providers.synced_at",
                        "movie_ott_providers.watch_url",
                        "movie_people.character_name",
                        "movie_people.display_order");
    }

    @Test
    @DisplayName("도메인 외래키는 물리 삭제를 전파하지 않는다")
    void restrictsPhysicalDeletesAcrossDomainForeignKeys() {
        assertThat(
                        column(
                                "SELECT rc.constraint_name || ':' || rc.delete_rule"
                                        + " FROM information_schema.referential_constraints rc"
                                        + " JOIN information_schema.table_constraints tc"
                                        + " ON tc.constraint_catalog = rc.constraint_catalog"
                                        + " AND tc.constraint_schema = rc.constraint_schema"
                                        + " AND tc.constraint_name = rc.constraint_name"
                                        + " WHERE tc.table_schema = 'public'"
                                        + " AND tc.table_name IN ('movie_genres', 'movie_countries',"
                                        + " 'movie_people', 'movie_ott_providers', 'watch_records',"
                                        + " 'ratings')"))
                .containsExactlyInAnyOrder(
                        "fk_movie_genres_movie_id:RESTRICT",
                        "fk_movie_genres_genre_id:RESTRICT",
                        "fk_movie_countries_movie_id:RESTRICT",
                        "fk_movie_people_movie_id:RESTRICT",
                        "fk_movie_people_person_id:RESTRICT",
                        "fk_movie_ott_providers_movie_id:RESTRICT",
                        "fk_movie_ott_providers_ott_id:RESTRICT",
                        "fk_watch_records_user_id:RESTRICT",
                        "fk_watch_records_movie_id:RESTRICT",
                        "fk_watch_records_ott_id:RESTRICT",
                        "fk_ratings_user_id:RESTRICT",
                        "fk_ratings_movie_id:RESTRICT",
                        "fk_ratings_watch_record_id:RESTRICT");
    }

    @Test
    @DisplayName("watch_record_id와 movielens_id에는 근거 없는 유일 인덱스가 없다")
    void doesNotAddUnconfirmedUniqueIndexes() {
        assertThat(
                        count(
                                "SELECT count(*) FROM pg_index i"
                                        + " JOIN pg_class t ON t.oid = i.indrelid"
                                        + " WHERE t.relname = 'ratings' AND i.indisunique"
                                        + " AND pg_get_indexdef(i.indexrelid)"
                                        + " LIKE '%(watch_record_id)%'"))
                .isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM pg_index i"
                                        + " JOIN pg_class t ON t.oid = i.indrelid"
                                        + " WHERE t.relname = 'movies' AND i.indisunique"
                                        + " AND pg_get_indexdef(i.indexrelid) LIKE '%(movielens_id)%'"))
                .isZero();
    }

    @Test
    @DisplayName("확정된 조회 경로를 지원하는 관계·평가 인덱스가 생성된다")
    void createsIndexesForConfirmedQueryPaths() {
        assertThat(
                        column(
                                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'"
                                        + " ORDER BY indexname"))
                .contains(
                        "ix_movie_genres_genre_id",
                        "ix_movie_countries_country_code",
                        "ix_movie_people_person_id",
                        "ix_movie_ott_providers_ott_id",
                        "ix_ratings_movie_id",
                        "ix_ratings_watch_record_id",
                        "ix_watch_records_user_id_clicked_at",
                        "uix_watch_records_active_user_movie");

        assertThat(
                        count(
                                "SELECT count(*) FROM pg_indexes"
                                        + " WHERE schemaname = 'public'"
                                        + " AND indexname = 'ix_watch_records_user_id_clicked_at'"
                                        + " AND indexdef LIKE '%(user_id, clicked_at DESC NULLS LAST,"
                                        + " id DESC)%'"))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM pg_indexes"
                                        + " WHERE schemaname = 'public'"
                                        + " AND indexname = 'ix_ratings_watch_record_id'"
                                        + " AND indexdef LIKE '%(watch_record_id)%'"))
                .isEqualTo(1);
    }

    // --- helpers ---------------------------------------------------------

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

    private static long insertGenre(String code, int tmdbGenreId) throws SQLException {
        return insertReturningId(
                "INSERT INTO genres (code, name, tmdb_genre_id) VALUES ('"
                        + code
                        + "', '"
                        + code
                        + "', "
                        + tmdbGenreId
                        + ") RETURNING id");
    }

    private static long insertPerson(int tmdbPersonId, String name) throws SQLException {
        return insertReturningId(
                "INSERT INTO people (tmdb_person_id, name) VALUES ("
                        + tmdbPersonId
                        + ", '"
                        + name
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
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
