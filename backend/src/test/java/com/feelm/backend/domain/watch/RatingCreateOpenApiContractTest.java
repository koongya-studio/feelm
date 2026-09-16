package com.feelm.backend.domain.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.support.SharedTestRuntime;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("평가 생성 OpenAPI 계약 — API-036")
class RatingCreateOpenApiContractTest {

    private static final String PATH = "/api/v1/movies/{movieId}/ratings";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("경로·인증·요청·성공과 오류 응답을 계약대로 문서화한다")
    void documentsRatingCreateContract() throws Exception {
        JsonNode document = document();
        JsonNode operation = document.path("paths").path(PATH).path("post");

        assertThat(operation.path("summary").asText()).isEqualTo("평가 생성");
        assertThat(operation.path("security"))
                .isEqualTo(objectMapper.readTree("[{\"bearerAuth\":[]}]"));

        JsonNode movieId = operation.path("parameters").path(0);
        assertThat(movieId.path("name").asText()).isEqualTo("movieId");
        assertThat(movieId.path("in").asText()).isEqualTo("path");
        assertThat(movieId.path("required").asBoolean()).isTrue();
        assertThat(movieId.path("schema").path("minimum").asLong()).isEqualTo(1L);

        JsonNode request =
                resolve(
                        document,
                        operation
                                .path("requestBody")
                                .path("content")
                                .path("application/json")
                                .path("schema"));
        assertThat(request.path("properties").properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("score", "comment", "visibility");
        assertThat(request.path("properties").has("scoreStepValid")).isFalse();
        assertThat(request.path("properties").has("commentVisibilityValid")).isFalse();
        assertThat(values(request.path("required"))).contains("score");
        JsonNode score = request.path("properties").path("score");
        assertThat(score.path("minimum").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(score.path("maximum").decimalValue()).isEqualByComparingTo("5.0");
        assertThat(score.path("multipleOf").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(values(request.path("properties").path("visibility").path("enum")))
                .containsExactlyInAnyOrder("PUBLIC", "PRIVATE");

        JsonNode responses = operation.path("responses");
        assertThat(responses.properties())
                .extracting(Map.Entry::getKey)
                .contains("201", "400", "401", "404", "409", "500");
        assertSchema(responses, "201", "RatingCreateResponse");
        for (String code : List.of("400", "401", "404", "409", "500")) {
            assertSchema(responses, code, "ErrorResponse");
        }

        JsonNode response =
                document.path("components").path("schemas").path("RatingCreateResponse");
        assertThat(response.path("properties").properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "ratingId",
                        "movieId",
                        "score",
                        "comment",
                        "visibility",
                        "frame",
                        "popcorn",
                        "watchStatus",
                        "createdAt");
    }

    private void assertSchema(JsonNode responses, String code, String name) {
        assertThat(
                        responses
                                .path(code)
                                .path("content")
                                .path("application/json")
                                .path("schema")
                                .path("$ref")
                                .asText())
                .isEqualTo("#/components/schemas/" + name);
    }

    private JsonNode resolve(JsonNode document, JsonNode schema) {
        String reference = schema.path("$ref").asText();
        return reference.isEmpty()
                ? schema
                : document.path("components")
                        .path("schemas")
                        .path(reference.substring(reference.lastIndexOf('/') + 1));
    }

    private JsonNode document() throws Exception {
        String json =
                mockMvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith("application/json"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
    }

    private static List<String> values(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }
}
