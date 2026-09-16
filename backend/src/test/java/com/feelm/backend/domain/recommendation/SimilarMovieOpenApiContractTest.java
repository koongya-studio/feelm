package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.support.SharedTestRuntime;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        properties = {
            "springdoc.api-docs.enabled=true",
            "springdoc.swagger-ui.enabled=true",
            "feelm.auth.mock.enabled=false"
        })
@AutoConfigureMockMvc
@SharedTestRuntime
class SimilarMovieOpenApiContractTest {

    private static final String PATH = "/api/v1/movies/{movieId}/similar";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void documentsApi031Contract() throws Exception {
        JsonNode document = document();
        JsonNode operation = document.path("paths").path(PATH).path("get");
        assertThat(operation.isObject()).isTrue();
        assertThat(
                        StreamSupport.stream(operation.path("parameters").spliterator(), false)
                                .map(parameter -> parameter.path("name").asText()))
                .containsExactlyInAnyOrder("movieId", "size");
        assertThat(operation.path("responses").path("200").isObject()).isTrue();

        JsonNode schemas = document.path("components").path("schemas");
        JsonNode response = schemas.path("SimilarMoviesResponse").path("properties");
        assertThat(response.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("baseMovieId", "items");
        JsonNode item = schemas.path("SimilarMovieItemResponse").path("properties");
        assertThat(item.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "movieId", "title", "posterUrl", "averageRating", "similarity");
        assertThat(item.has("releaseYear")).isFalse();
        assertThat(item.has("reason")).isFalse();
    }

    private JsonNode document() throws Exception {
        String body =
                mvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return mapper.readTree(body);
    }
}
