package com.feelm.backend.domain.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.SecurityConfig;
import com.feelm.backend.support.SharedPostgresContractTest;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@Import(PublicReviewQueryRecorder.Configuration.class)
@SharedTestRuntime
@DisplayName("공개 한줄평 OpenAPI 계약 — API-032")
class PublicReviewOpenApiContractTest extends SharedPostgresContractTest {

    private static final String PATH = "/api/v1/movies/{movieId}/reviews";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("T29 path·query·응답 code와 schema를 실제 api-docs에 기술한다")
    void openApiDescribesPublicReviewContract() throws Exception {
        JsonNode operation = document().path("paths").path(PATH).path("get");

        assertThat(operation.isObject()).isTrue();
        assertThat(operation.path("summary").asText()).isNotBlank();
        assertThat(operation.path("description").asText()).contains("회원", "ACTIVE", "차단");
        List<JsonNode> parameters =
                StreamSupport.stream(operation.path("parameters").spliterator(), false).toList();
        assertThat(parameters)
                .extracting(parameter -> parameter.path("name").asText())
                .containsExactlyInAnyOrder("movieId", "cursor", "size");
        JsonNode movieId = parameter(parameters, "movieId");
        assertThat(movieId.path("in").asText()).isEqualTo("path");
        assertThat(movieId.path("required").asBoolean()).isTrue();
        assertThat(movieId.path("schema").path("type").asText()).isEqualTo("integer");
        assertThat(movieId.path("schema").path("format").asText()).isEqualTo("int64");
        assertThat(movieId.path("schema").path("minimum").asLong()).isEqualTo(1L);
        JsonNode cursor = parameter(parameters, "cursor");
        assertThat(cursor.path("in").asText()).isEqualTo("query");
        assertThat(cursor.path("required").asBoolean()).isFalse();
        JsonNode size = parameter(parameters, "size").path("schema");
        assertThat(size.path("type").asText()).isEqualTo("integer");
        assertThat(size.path("default").asInt()).isEqualTo(20);
        assertThat(size.path("minimum").asInt()).isEqualTo(1);
        assertThat(size.path("maximum").asInt()).isEqualTo(50);

        JsonNode responses = operation.path("responses");
        assertThat(responses.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("200", "400", "401", "404", "500");
        for (String code : List.of("200", "400", "401", "404", "500")) {
            String expected = code.equals("200") ? "PublicReviewPageResponse" : "ErrorResponse";
            assertThat(
                            responses
                                    .path(code)
                                    .path("content")
                                    .path("application/json")
                                    .path("schema")
                                    .path("$ref")
                                    .asText())
                    .isEqualTo("#/components/schemas/" + expected);
        }
        assertThat(responses.path("401").path("description").asText())
                .contains("UNAUTHORIZED", "TOKEN_EXPIRED");
    }

    @Test
    @DisplayName("T49 ErrorResponse는 code·message만 required이고 details는 optional이다")
    void errorResponseRequiresCodeAndMessageOnly() throws Exception {
        JsonNode errorResponse =
                document().path("components").path("schemas").path("ErrorResponse");

        assertThat(required(errorResponse)).containsExactlyInAnyOrder("code", "message");
        assertThat(errorResponse.path("properties").has("details")).isTrue();
        assertThat(required(errorResponse)).doesNotContain("details");
    }

    @Test
    @DisplayName("T30 API-032를 선택 인증이 아닌 bearer 필수로 표시한다")
    void openApiMarksBearerAuthenticationRequired() throws Exception {
        assertThat(SecurityConfig.isPublicApi(HttpMethod.GET, "/api/v1/movies/161/reviews"))
                .isFalse();
        assertThat(document().path("paths").path(PATH).path("get").path("security"))
                .isEqualTo(objectMapper.readTree("[{\"bearerAuth\": []}]"));
    }

    @Test
    @DisplayName("T45 spoiler를 required boolean으로 기술하고 기존 item·page 계약을 유지한다")
    void openApiRequiresBooleanSpoiler() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");
        JsonNode page = schemas.path("PublicReviewPageResponse");
        JsonNode item = schemas.path("PublicReviewItemResponse");
        JsonNode user = schemas.path("PublicReviewUserResponse");

        assertRequiredEqualsProperties(page);
        assertRequiredEqualsProperties(item);
        assertRequiredEqualsProperties(user);
        assertThat(page.path("properties").path("items").path("items").path("$ref").asText())
                .isEqualTo("#/components/schemas/PublicReviewItemResponse");
        JsonNode nextCursor = page.path("properties").path("nextCursor");
        assertThat(
                        StreamSupport.stream(nextCursor.path("type").spliterator(), false)
                                .map(JsonNode::asText)
                                .toList())
                .contains("string", "null");
        JsonNode properties = item.path("properties");
        assertThat(properties.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "ratingId", "user", "score", "comment", "mine", "spoiler", "createdAt");
        assertThat(properties.path("mine").path("type").asText()).isEqualTo("boolean");
        JsonNode spoiler = properties.path("spoiler");
        assertThat(spoiler.path("type").asText()).isEqualTo("boolean");
        assertThat(spoiler.path("description").asText()).isEqualTo("작성자가 한줄평을 스포일러로 표시했는지 여부");
        assertThat(spoiler.path("example").asBoolean()).isTrue();
        assertThat(properties.path("score").path("type").asText()).isEqualTo("number");
        assertThat(properties.path("createdAt").path("type").asText()).isEqualTo("string");
        assertThat(properties.path("createdAt").path("format").asText()).isEqualTo("date-time");
        assertThat(user.path("properties").properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("userId", "nickname");
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

    private static JsonNode parameter(List<JsonNode> parameters, String name) {
        return parameters.stream()
                .filter(parameter -> parameter.path("name").asText().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static void assertRequiredEqualsProperties(JsonNode schema) {
        assertThat(
                        StreamSupport.stream(schema.path("required").spliterator(), false)
                                .map(JsonNode::asText)
                                .toList())
                .containsExactlyInAnyOrderElementsOf(
                        schema.path("properties").properties().stream()
                                .map(Map.Entry::getKey)
                                .toList());
    }

    private static List<String> required(JsonNode schema) {
        return StreamSupport.stream(schema.path("required").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }
}
