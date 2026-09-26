package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.recommendation.service.DismissalListCursor;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
class DismissalListApiContractTest extends SharedPostgresContractTest {
    private static final String PATH = "/api/v1/users/me/dismissals";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users, movies RESTART IDENTITY CASCADE");
        for (long id : new long[] {10, 20}) {
            jdbc.update(
                    "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                            + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                    id,
                    "dismiss" + id + "@example.test",
                    "dismiss" + id);
        }
        for (long id : new long[] {100, 101, 102, 103}) {
            jdbc.update(
                    "INSERT INTO movies(id,tmdb_id,title,poster_url) VALUES (?,?,?,?)",
                    id,
                    id + 10000,
                    "영화" + id,
                    id == 100 ? "https://image.test/100.jpg" : null);
        }
    }

    @Test
    void ownListIncludesMoviesWithoutRecommendationHistory() throws Exception {
        dismissal(1, 10, 100, "2026-09-25T00:00:00Z");
        dismissal(2, 20, 103, "2026-09-27T00:00:00Z");
        dismissal(3, 10, 101, "2026-09-26T00:00:00Z");
        mvc.perform(request(10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].movie.movieId").value(101))
                .andExpect(jsonPath("$.items[0].movie.posterUrl").value(nullValue()))
                .andExpect(jsonPath("$.items[0].dismissedAt").value("2026-09-26T00:00:00Z"))
                .andExpect(
                        jsonPath("$.items[1].movie.posterUrl").value("https://image.test/100.jpg"))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void stablePaginationAcrossTimestampTiesAndNewDismissal() throws Exception {
        for (int id = 1; id <= 3; id++) dismissal(id, 10, 99 + id, "2026-09-25T00:00:00Z");
        MvcResult first =
                mvc.perform(request(10).param("size", "1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items[0].dismissalId").value(3))
                        .andExpect(jsonPath("$.hasNext").value(true))
                        .andReturn();
        String cursor = cursor(first);
        dismissal(4, 10, 103, "2026-09-27T00:00:00Z");
        MvcResult second =
                mvc.perform(request(10).param("size", "1").param("cursor", cursor))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items[0].dismissalId").value(2))
                        .andExpect(jsonPath("$.hasNext").value(true))
                        .andReturn();
        mvc.perform(request(10).param("size", "2").param("cursor", cursor(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].dismissalId").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void invalidSizeAndCursorAreValidationFailures() throws Exception {
        for (String size : new String[] {"0", "51", "1.5", "abc", "2147483648", ""}) {
            mvc.perform(request(10).param("size", size))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        for (String value :
                new String[] {
                    "",
                    "%%%",
                    "a".repeat(129),
                    new DismissalListCursor(Instant.parse("2026-09-25T00:00:00Z"), 99).encode(10),
                    new DismissalListCursor(Instant.parse("9999-01-01T00:00:00Z"), 1).encode(10)
                }) {
            mvc.perform(request(10).param("cursor", value))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        dismissal(1, 10, 100, "2026-09-25T00:00:00Z");
        dismissal(2, 10, 101, "2026-09-25T00:00:00Z");
        String cursor = cursor(mvc.perform(request(10).param("size", "1")).andReturn());
        mvc.perform(request(20).param("cursor", cursor)).andExpect(status().isBadRequest());
        String altered =
                new DismissalListCursor(Instant.parse("2026-09-24T00:00:00Z"), 2).encode(10);
        mvc.perform(request(10).param("cursor", altered)).andExpect(status().isBadRequest());
    }

    @Test
    void emptyListAndUnauthorizedViewers() throws Exception {
        mvc.perform(request(10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET status = 'WITHDRAWN' WHERE id = 10");
        mvc.perform(request(10)).andExpect(status().isUnauthorized());
    }

    @Test
    void openApiMatchesListContract() throws Exception {
        var doc =
                mapper.readTree(
                        mvc.perform(get("/v3/api-docs"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        var operation = doc.path("paths").path(PATH).path("get");
        assertThat(operation.path("parameters").toString()).contains("cursor", "size", "50", "20");
        assertThat(operation.path("responses").has("400")).isTrue();
        assertThat(operation.path("responses").has("401")).isTrue();
        String responseRef =
                operation
                        .path("responses")
                        .path("200")
                        .path("content")
                        .path("*/*")
                        .path("schema")
                        .path("$ref")
                        .asText();
        // Springdoc may use application/json instead of */*.
        if (responseRef.isEmpty())
            responseRef =
                    operation
                            .path("responses")
                            .path("200")
                            .path("content")
                            .path("application/json")
                            .path("schema")
                            .path("$ref")
                            .asText();
        assertThat(responseRef).endsWith("DismissalListResponse");
        assertThat(
                        doc.path("components")
                                .path("schemas")
                                .path("DismissalListResponse")
                                .path("properties")
                                .toString())
                .contains("items", "nextCursor", "hasNext");
        var schemas = doc.path("components").path("schemas");
        assertThat(
                        schemas.path("DismissalListResponse")
                                .path("properties")
                                .path("nextCursor")
                                .path("type")
                                .toString())
                .contains("null");
        assertThat(
                        schemas.path("DismissalMovieResponse")
                                .path("properties")
                                .path("posterUrl")
                                .path("type")
                                .toString())
                .contains("null");
        assertThat(schemas.path("DismissalListResponse").path("required").toString())
                .contains("items", "nextCursor", "hasNext");
    }

    private MockHttpServletRequestBuilder request(long userId) {
        return get(PATH).header("Authorization", "Bearer " + tokens.createAccessToken(userId));
    }

    private String cursor(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString())
                .path("nextCursor")
                .asText();
    }

    private void dismissal(long id, long userId, long movieId, String time) {
        jdbc.update(
                "INSERT INTO user_movie_dismissals(id,user_id,movie_id,created_at) VALUES (?,?,?,?)",
                id,
                userId,
                movieId,
                OffsetDateTime.parse(time));
    }
}
