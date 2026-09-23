package com.feelm.backend.domain.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.SecurityConfig;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
class MyRatingOpenApiContractTest extends SharedPostgresContractTest {

    private static final String PATH = "/api/v1/users/me/ratings";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void actualJson() throws Exception {
        JsonNode apiDocs = document();
        JsonNode operation = apiDocs.path("paths").path(PATH).path("get");
        assertThat(operation.isObject()).isTrue();
        assertThat(SecurityConfig.isPublicApi(HttpMethod.GET, PATH)).isFalse();
        assertThat(operation.path("security"))
                .isEqualTo(objectMapper.readTree("[{\"bearerAuth\": []}]"));
        List<JsonNode> parameters =
                StreamSupport.stream(operation.path("parameters").spliterator(), false).toList();
        assertThat(parameters)
                .extracting(parameter -> parameter.path("name").asText())
                .containsExactlyInAnyOrder("filter", "sort", "cursor", "size");
        assertThat(values(parameter(parameters, "filter")))
                .containsExactlyInAnyOrder("ALL", "COMMENTED", "UNRATED");
        assertThat(values(parameter(parameters, "sort")))
                .containsExactlyInAnyOrder("LATEST", "SCORE_DESC", "SCORE_ASC", "OLDEST");
        JsonNode size = parameter(parameters, "size").path("schema");
        assertThat(size.path("default").asInt()).isEqualTo(20);
        assertThat(size.path("minimum").asInt()).isEqualTo(1);
        assertThat(size.path("maximum").asInt()).isEqualTo(50);

        JsonNode responses = operation.path("responses");
        assertThat(responses.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("200", "400", "401", "500");
        assertThat(
                        responses
                                .path("200")
                                .path("content")
                                .path("application/json")
                                .path("schema")
                                .path("$ref")
                                .asText())
                .isEqualTo("#/components/schemas/MyRatingPageResponse");
        for (String status : List.of("400", "401", "500")) {
            assertThat(
                            responses
                                    .path(status)
                                    .path("content")
                                    .path("application/json")
                                    .path("schema")
                                    .path("$ref")
                                    .asText())
                    .isEqualTo("#/components/schemas/ErrorResponse");
        }
        assertThat(responses.path("401").path("description").asText())
                .contains("UNAUTHORIZED", "TOKEN_EXPIRED");
        JsonNode schemas = apiDocs.path("components").path("schemas");
        JsonNode page = schemas.path("MyRatingPageResponse");
        JsonNode item = schemas.path("MyRatingItemResponse");
        assertThat(page.path("properties").path("items").path("items").path("$ref").asText())
                .isEqualTo("#/components/schemas/MyRatingItemResponse");
        assertThat(required(item))
                .containsExactlyInAnyOrder(
                        "itemType",
                        "ratingId",
                        "movie",
                        "score",
                        "comment",
                        "visibility",
                        "spoiler",
                        "frameId",
                        "createdAt",
                        "activityAt");
        assertThat(values(item.path("properties").path("itemType")))
                .containsExactlyInAnyOrder("RATED", "UNRATED");
        for (String field :
                List.of(
                        "ratingId",
                        "score",
                        "comment",
                        "visibility",
                        "spoiler",
                        "frameId",
                        "createdAt",
                        "activityAt")) {
            JsonNode schema = item.path("properties").path(field);
            assertThat(schema.path("type").toString()).as(field).contains("null");
        }
        assertThat(required(page)).containsExactlyInAnyOrder("items", "nextCursor", "hasNext");
    }

    private JsonNode document() throws Exception {
        return objectMapper.readTree(
                mockMvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private static JsonNode parameter(List<JsonNode> parameters, String name) {
        return parameters.stream()
                .filter(parameter -> parameter.path("name").asText().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> values(JsonNode schemaOrParameter) {
        JsonNode schema =
                schemaOrParameter.has("schema")
                        ? schemaOrParameter.path("schema")
                        : schemaOrParameter;
        return StreamSupport.stream(schema.path("enum").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    private static List<String> required(JsonNode schema) {
        return StreamSupport.stream(schema.path("required").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }
}
