package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("영화 임베딩 마이그레이션")
class MovieEmbeddingMigrationTest {

    private static final int EMBEDDING_DIMENSIONS = 768;
    private static final MigrationPostgresSupport.MigrationTestDatabase DATABASE =
            MigrationPostgresSupport.database("movie_embedding");

    @BeforeAll
    static void migrate() {
        DATABASE.flyway().migrate();
    }

    @AfterAll
    static void dropDatabase() {
        DATABASE.close();
    }

    @Test
    @DisplayName("별도 식별자와 영화별 768차원 임베딩 계약을 생성한다")
    void createsMovieEmbeddingTable() {
        assertThat(
                        column(
                                "SELECT column_name FROM information_schema.columns"
                                        + " WHERE table_schema = 'public'"
                                        + " AND table_name = 'movie_embeddings'"))
                .containsExactlyInAnyOrder(
                        "id", "movie_id", "embedding", "model_version", "updated_at");
        assertThat(
                        textValue(
                                "SELECT format_type(a.atttypid, a.atttypmod)"
                                        + " FROM pg_attribute a"
                                        + " JOIN pg_class c ON c.oid = a.attrelid"
                                        + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                        + " WHERE n.nspname = 'public'"
                                        + " AND c.relname = 'movie_embeddings'"
                                        + " AND a.attname = 'embedding'"))
                .isEqualTo("vector(768)");
        assertThat(
                        textValue(
                                "SELECT data_type || ':' || character_maximum_length"
                                        + " FROM information_schema.columns"
                                        + " WHERE table_schema = 'public'"
                                        + " AND table_name = 'movie_embeddings'"
                                        + " AND column_name = 'model_version'"))
                .isEqualTo("character varying:50");
        assertThat(
                        column(
                                "SELECT column_name FROM information_schema.columns"
                                        + " WHERE table_schema = 'public'"
                                        + " AND table_name = 'movie_embeddings'"
                                        + " AND is_nullable = 'NO'"))
                .containsExactlyInAnyOrder(
                        "id", "movie_id", "embedding", "model_version", "updated_at");
        assertThat(
                        textValue(
                                "SELECT identity_generation FROM information_schema.columns"
                                        + " WHERE table_schema = 'public'"
                                        + " AND table_name = 'movie_embeddings'"
                                        + " AND column_name = 'id'"))
                .isEqualTo("BY DEFAULT");
    }

    @Test
    @DisplayName("올바른 임베딩만 저장하고 갱신 시각 기본값을 기록한다")
    void enforcesEmbeddingContract() throws SQLException {
        long movieId = insertMovie(419001, "임베딩 계약 영화");
        execute(
                "INSERT INTO movie_embeddings(movie_id, embedding, model_version) VALUES ("
                        + movieId
                        + ", '"
                        + vector(1, 0)
                        + "', 'multilingual-e5-base')");

        assertThat(column("SELECT model_version FROM movie_embeddings WHERE movie_id = " + movieId))
                .containsExactly("multilingual-e5-base");
        assertThat(
                        longValue(
                                "SELECT count(*) FROM movie_embeddings"
                                        + " WHERE movie_id = "
                                        + movieId
                                        + " AND updated_at IS NOT NULL"))
                .isOne();
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO movie_embeddings(movie_id, embedding, model_version)"
                                                + " VALUES ("
                                                + insertMovie(419002, "잘못된 차원 영화")
                                                + ", '[1,0]', 'multilingual-e5-base')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("expected 768 dimensions");
        long missingModelMovieId = insertMovie(419003, "모델 누락 영화");
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO movie_embeddings(movie_id, embedding, model_version)"
                                                + " VALUES ("
                                                + missingModelMovieId
                                                + ", '"
                                                + vector(1, 0)
                                                + "', NULL)"))
                .isInstanceOf(SQLException.class)
                .extracting(exception -> ((SQLException) exception).getSQLState())
                .isEqualTo("23502");
    }

    @Test
    @DisplayName("영화가 삭제되면 임베딩도 삭제한다")
    void deletesEmbeddingWithMovie() throws SQLException {
        long movieId = insertMovie(419004, "삭제 영화");
        insertEmbedding(movieId, vector(1, 0));

        execute("DELETE FROM movies WHERE id = " + movieId);

        assertThat(longValue("SELECT count(*) FROM movie_embeddings WHERE movie_id = " + movieId))
                .isZero();
    }

    @Test
    @DisplayName("영화마다 현재 임베딩을 하나만 허용한다")
    void allowsOnlyOneEmbeddingPerMovie() throws SQLException {
        long movieId = insertMovie(419005, "중복 영화");
        insertEmbedding(movieId, vector(1, 0));

        assertThatThrownBy(() -> insertEmbedding(movieId, vector(0, 1)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_movie_embeddings_movie_id");
    }

    @Test
    @DisplayName("코사인 거리용 HNSW 인덱스를 생성하고 조회 계획에서 사용한다")
    void createsCosineHnswIndex() throws SQLException {
        assertThat(
                        textValue(
                                "SELECT indexdef FROM pg_indexes"
                                        + " WHERE schemaname = 'public'"
                                        + " AND indexname = 'ix_movie_embeddings_embedding_hnsw'"))
                .contains("USING hnsw")
                .contains("embedding vector_cosine_ops");

        long baseMovieId = insertMovie(419009, "인덱스 기준 영화");
        insertEmbedding(baseMovieId, vector(1, 0), "index-plan-test");
        for (int index = 0; index < 50; index++) {
            long movieId = insertMovie(419100 + index, "인덱스 후보 " + index);
            insertEmbedding(movieId, vector(1, index + 1), "index-plan-test");
        }

        assertThat(explainCosineSearch(vector(1, 0)))
                .contains("ix_movie_embeddings_embedding_hnsw");
    }

    @Test
    @DisplayName("샘플 임베딩을 코사인 거리순으로 조회한다")
    void ordersSampleEmbeddingsByCosineDistance() throws SQLException {
        long baseMovieId = insertMovie(419006, "기준 영화");
        long nearMovieId = insertMovie(419007, "가까운 영화");
        long farMovieId = insertMovie(419008, "먼 영화");
        insertEmbedding(baseMovieId, vector(1, 0), "similarity-test");
        insertEmbedding(nearMovieId, vector(10, 0), "similarity-test");
        insertEmbedding(farMovieId, vector(0, 1), "similarity-test");

        assertThat(cosineSearch(vector(1, 0), "similarity-test", baseMovieId, 2))
                .containsExactly(nearMovieId, farMovieId);
    }

    @Test
    @DisplayName("마이그레이션을 다시 적용해도 추가 실행하지 않는다")
    void isSafeToReapply() {
        assertThat(DATABASE.flyway().migrate().migrationsExecuted).isZero();
        assertThat(DATABASE.flyway().validateWithResult().validationSuccessful).isTrue();
    }

    private static void insertEmbedding(long movieId, String embedding) throws SQLException {
        insertEmbedding(movieId, embedding, "multilingual-e5-base");
    }

    private static void insertEmbedding(long movieId, String embedding, String modelVersion)
            throws SQLException {
        execute(
                "INSERT INTO movie_embeddings(movie_id, embedding, model_version) VALUES ("
                        + movieId
                        + ", '"
                        + embedding
                        + "', '"
                        + modelVersion
                        + "')");
    }

    private static long insertMovie(int tmdbId, String title) throws SQLException {
        return longValue(
                "INSERT INTO movies(tmdb_id, title) VALUES ("
                        + tmdbId
                        + ", '"
                        + title
                        + "') RETURNING id");
    }

    private static String vector(double first, double second) {
        List<String> values = new ArrayList<>(EMBEDDING_DIMENSIONS);
        values.add(Double.toString(first));
        values.add(Double.toString(second));
        for (int index = 2; index < EMBEDDING_DIMENSIONS; index++) {
            values.add("0");
        }
        return "[" + String.join(",", values) + "]";
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
        } catch (SQLException exception) {
            throw new IllegalStateException(sql, exception);
        }
    }

    private static List<Long> cosineSearch(
            String queryVector, String modelVersion, long excludedMovieId, int limit)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT movie_id FROM movie_embeddings"
                                        + " WHERE model_version = ? AND movie_id <> ?"
                                        + " ORDER BY embedding <=> CAST(? AS vector) ASC LIMIT ?")) {
            statement.setString(1, modelVersion);
            statement.setLong(2, excludedMovieId);
            statement.setString(3, queryVector);
            statement.setInt(4, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Long> values = new ArrayList<>();
                while (resultSet.next()) {
                    values.add(resultSet.getLong(1));
                }
                return values;
            }
        }
    }

    private static String explainCosineSearch(String queryVector) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement settings = connection.createStatement()) {
                settings.execute("SET LOCAL enable_seqscan = off");
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "EXPLAIN (COSTS OFF) SELECT movie_id FROM movie_embeddings"
                                    + " ORDER BY embedding <=> CAST(? AS vector) ASC LIMIT 10")) {
                statement.setString(1, queryVector);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<String> lines = new ArrayList<>();
                    while (resultSet.next()) {
                        lines.add(resultSet.getString(1));
                    }
                    connection.rollback();
                    return String.join("\n", lines);
                }
            }
        }
    }

    private static String textValue(String sql) {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(sql, exception);
        }
    }

    private static long longValue(String sql) throws SQLException {
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

    private static Connection connect() throws SQLException {
        return DATABASE.connect();
    }
}
