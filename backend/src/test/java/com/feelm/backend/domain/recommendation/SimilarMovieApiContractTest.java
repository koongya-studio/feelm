package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.SecurityConfig;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** API-031: 실제 pgvector PostgreSQL을 사용하는 유사 영화 HTTP 계약 테스트. */
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.task.scheduling.enabled=false",
            "feelm.auth.mock.enabled=false"
        })
@AutoConfigureMockMvc
@DisplayName("API-031 유사 영화 조회")
class SimilarMovieApiContractTest extends SharedPostgresContractTest {

    private static final int DIMENSIONS = 768;
    private static final String PATH = "/api/v1/movies/{movieId}/similar";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TokenProvider tokens;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE movie_embeddings, movies RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("코사인 거리가 가까운 순서로 기준 영화를 제외해 반환한다")
    void returnsNearestMoviesInCosineOrder() throws Exception {
        movie(100, "기준 영화", null, "4.50");
        movie(101, "가까운 영화", "https://image.test/101.jpg", "4.20");
        movie(102, "먼 영화", null, "3.10");
        movie(103, "다른 모델 영화", null, "5.00");
        embedding(100, vector(1, 0), "model-v1");
        embedding(101, vector(10, 0), "model-v1");
        embedding(102, vector(0, 1), "model-v1");
        embedding(103, vector(1, 0), "model-v2");

        mvc.perform(get(PATH, 100).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseMovieId").value(100))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].movieId").value(101))
                .andExpect(jsonPath("$.items[0].title").value("가까운 영화"))
                .andExpect(jsonPath("$.items[0].posterUrl").value("https://image.test/101.jpg"))
                .andExpect(jsonPath("$.items[0].averageRating").value(4.2))
                .andExpect(jsonPath("$.items[0].similarity").value(1.0))
                .andExpect(jsonPath("$.items[1].movieId").value(102))
                .andExpect(jsonPath("$.items[1].similarity").value(0.0))
                .andExpect(jsonPath("$.items[?(@.movieId == 100)]").isEmpty())
                .andExpect(jsonPath("$.items[?(@.movieId == 103)]").isEmpty())
                .andExpect(jsonPath("$.items[0].releaseYear").doesNotExist())
                .andExpect(jsonPath("$.items[0].reason").doesNotExist());
    }

    @Test
    @DisplayName("size를 생략하면 10개, 최대값을 요청하면 20개를 반환한다")
    void usesDefaultSizeAndAcceptsMaximumTwenty() throws Exception {
        movie(100, "기준 영화", null, "0");
        embedding(100, vector(1, 0), "model-v1");
        for (int index = 1; index <= 21; index++) {
            movie(100 + index, "후보 " + index, null, "0");
            embedding(100 + index, vector(1, index), "model-v1");
        }

        mvc.perform(get(PATH, 100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(10));
        mvc.perform(get(PATH, 100).param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "21", "abc", "0x14", "2 0"})
    @DisplayName("size는 1 이상 20 이하의 정수여야 한다")
    void rejectsInvalidSize(String size) throws Exception {
        mvc.perform(get(PATH, 100).param("size", size))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("기준 영화가 없으면 404를 반환한다")
    void returnsNotFoundWhenBaseMovieDoesNotExist() throws Exception {
        mvc.perform(get(PATH, 999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("기준 영화 임베딩이 없으면 503을 반환한다")
    void returnsUnavailableWhenBaseEmbeddingIsMissing() throws Exception {
        movie(100, "임베딩 없는 영화", null, "0");

        mvc.perform(get(PATH, 100))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECOMMENDATION_UNAVAILABLE"));
    }

    @Test
    @DisplayName("같은 모델의 후보가 없으면 빈 목록을 반환한다")
    void returnsEmptyItemsWhenNoCandidateUsesTheSameModel() throws Exception {
        movie(100, "기준 영화", null, "0");
        movie(101, "다른 모델 영화", null, "0");
        embedding(100, vector(1, 0), "model-v1");
        embedding(101, vector(1, 0), "model-v2");

        mvc.perform(get(PATH, 100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseMovieId").value(100))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("회원과 비회원에게 같은 결과를 반환한다")
    void allowsAnonymousAndAuthenticatedGet() throws Exception {
        movie(100, "기준 영화", null, "0");
        movie(101, "후보 영화", null, "0");
        embedding(100, vector(1, 0), "model-v1");
        embedding(101, vector(1, 0), "model-v1");

        String anonymous =
                mvc.perform(get(PATH, 100))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String authenticated =
                mvc.perform(
                                get(PATH, 100)
                                        .header(
                                                "Authorization",
                                                "Bearer " + tokens.createAccessToken(77)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(authenticated).isEqualTo(anonymous);
        assertThat(SecurityConfig.isPublicApi(HttpMethod.GET, "/api/v1/movies/100/similar"))
                .isTrue();
        assertThat(SecurityConfig.isPublicApi(HttpMethod.POST, "/api/v1/movies/100/similar"))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "0x64", "9223372036854775808"})
    @DisplayName("잘못된 비회원 movieId는 인증이 아니라 검증 오류로 응답한다")
    void invalidAnonymousMovieIdsReachValidation(String movieId) throws Exception {
        mvc.perform(get("/api/v1/movies/{movieId}/similar", movieId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("Top-K 쿼리가 HNSW 인덱스를 사용한다")
    void usesHnswIndexForTopKQuery() throws Exception {
        movie(100, "기준 영화", null, "0");
        embedding(100, vector(1, 0), "model-v1");
        for (int index = 1; index <= 40; index++) {
            movie(100 + index, "후보 " + index, null, "0");
            embedding(100 + index, vector(1, index), "model-v1");
        }

        assertThat(explainTopK(vector(1, 0))).contains("ix_movie_embeddings_embedding_hnsw");
    }

    private void movie(long id, String title, String posterUrl, String averageRating) {
        jdbc.update(
                "INSERT INTO movies(id,tmdb_id,title,poster_url,average_rating) VALUES (?,?,?,?,?)",
                id,
                Math.toIntExact(10_000 + id),
                title,
                posterUrl,
                new java.math.BigDecimal(averageRating));
    }

    private void embedding(long movieId, String vector, String modelVersion) {
        jdbc.update(
                "INSERT INTO movie_embeddings(movie_id,embedding,model_version)"
                        + " VALUES (?,CAST(? AS vector),?)",
                movieId,
                vector,
                modelVersion);
    }

    private String explainTopK(String baseEmbedding) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement settings = connection.createStatement()) {
                settings.execute("SET LOCAL enable_seqscan = off");
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "EXPLAIN (COSTS OFF)"
                                    + " SELECT movie_id FROM movie_embeddings"
                                    + " WHERE movie_id <> ? AND model_version = ?"
                                    + " ORDER BY embedding <=> CAST(? AS vector) ASC LIMIT 20")) {
                statement.setLong(1, 100);
                statement.setString(2, "model-v1");
                statement.setString(3, baseEmbedding);
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

    private static String vector(double first, double second) {
        List<String> values = new ArrayList<>(DIMENSIONS);
        values.add(Double.toString(first));
        values.add(Double.toString(second));
        for (int index = 2; index < DIMENSIONS; index++) {
            values.add("0");
        }
        return "[" + String.join(",", values) + "]";
    }
}
