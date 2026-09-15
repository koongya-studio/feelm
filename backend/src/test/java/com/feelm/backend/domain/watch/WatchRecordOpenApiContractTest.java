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

/** 어노테이션 존재가 아니라 실제 springdoc JSON으로 API-034·035 계약을 고정한다. */
@SpringBootTest(
        properties = {
            "springdoc.api-docs.enabled=true",
            "springdoc.swagger-ui.enabled=true",
            "feelm.auth.mock.enabled=false"
        })
@AutoConfigureMockMvc
@SharedTestRuntime
@DisplayName("감상 기록 조회·확인 OpenAPI 계약 — API-034·035")
class WatchRecordOpenApiContractTest {

    private static final String LIST_PATH = "/api/v1/watch-records";
    private static final String CONFIRM_PATH = "/api/v1/watch-records/{watchRecordId}/confirm";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("GET은 Bearer 인증·필터 enum·size 범위와 200/400/401/500을 문서화한다")
    void describesListOperationAndParameters() throws Exception {
        JsonNode operation = operation(LIST_PATH, "get");

        assertThat(operation.path("summary").asText()).isEqualTo("감상 기록 목록");
        assertThat(operation.path("description").asText()).contains("7일", "EXPIRED", "사용자와 필터 조건");
        assertBearer(operation);
        Map<String, JsonNode> parameters = parameters(operation);
        assertThat(parameters.keySet())
                .containsExactlyInAnyOrder("status", "confirmDue", "cursor", "size");

        JsonNode statusParameter = parameters.get("status");
        assertThat(statusParameter.path("in").asText()).isEqualTo("query");
        assertThat(statusParameter.path("schema").path("type").asText()).isEqualTo("array");
        assertThat(values(statusParameter.path("schema").path("items").path("enum")))
                .containsExactlyInAnyOrder("LINK_CLICKED", "WATCHED_UNRATED", "RATED", "EXPIRED");
        JsonNode confirmDue = parameters.get("confirmDue").path("schema");
        assertThat(confirmDue.path("type").asText()).isEqualTo("boolean");
        assertThat(confirmDue.path("default").asBoolean()).isFalse();
        JsonNode size = parameters.get("size").path("schema");
        assertThat(size.path("type").asText()).isEqualTo("integer");
        assertThat(size.path("minimum").asInt()).isEqualTo(1);
        assertThat(size.path("maximum").asInt()).isEqualTo(50);
        assertThat(size.path("default").asInt()).isEqualTo(20);

        assertResponseSchemas(
                operation.path("responses"),
                Map.of(
                        "400", "ErrorResponse",
                        "401", "ErrorResponse",
                        "500", "ErrorResponse"));
        assertThat(operation.path("responses").properties())
                .extracting(Map.Entry::getKey)
                .contains("200", "400", "401", "500");
    }

    @Test
    @DisplayName("GET 성공은 cursor page와 감상 항목의 enum·nullable UTC 시각을 문서화한다")
    void describesListResponseSchema() throws Exception {
        JsonNode responseSchema =
                operation(LIST_PATH, "get")
                        .path("responses")
                        .path("200")
                        .path("content")
                        .path("application/json")
                        .path("schema");
        JsonNode page = resolve(responseSchema);

        assertThat(page.path("properties").properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("items", "nextCursor", "hasNext");
        assertThat(values(page.path("required")))
                .containsExactlyInAnyOrder("items", "nextCursor", "hasNext");
        JsonNode item = resolve(page.path("properties").path("items").path("items"));
        assertThat(item.path("properties").properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "watchRecordId",
                        "movie",
                        "status",
                        "ott",
                        "clickedAt",
                        "confirmDueAt",
                        "expiresAt",
                        "watchedAt",
                        "ratingId");
        assertThat(values(item.path("required")))
                .containsExactlyInAnyOrder(
                        "watchRecordId",
                        "movie",
                        "status",
                        "ott",
                        "clickedAt",
                        "confirmDueAt",
                        "expiresAt",
                        "watchedAt",
                        "ratingId");
        assertThat(values(item.path("properties").path("status").path("enum")))
                .containsExactlyInAnyOrder("LINK_CLICKED", "WATCHED_UNRATED", "RATED", "EXPIRED");
        for (String field : List.of("clickedAt", "confirmDueAt", "expiresAt", "watchedAt")) {
            JsonNode time = item.path("properties").path(field);
            assertThat(time.path("format").asText()).isEqualTo("date-time");
            assertThat(values(time.path("type"))).contains("string", "null");
            assertThat(time.path("description").asText()).contains("UTC");
        }
        assertThat(values(item.path("properties").path("ratingId").path("type")))
                .contains("integer", "null");
        assertThat(item.path("properties").path("ott").path("description").asText())
                .contains("null");
    }

    @Test
    @DisplayName("POST는 Bearer 인증·JSON boolean·경로 ID와 모든 성공·오류 응답을 문서화한다")
    void describesConfirmOperationAndSchemas() throws Exception {
        JsonNode operation = operation(CONFIRM_PATH, "post");

        assertThat(operation.path("summary").asText()).isEqualTo("감상 확인 응답");
        assertThat(operation.path("description").asText())
                .contains("2일 이전", "WATCHED_UNRATED", "EXPIRED");
        assertBearer(operation);
        Map<String, JsonNode> parameters = parameters(operation);
        assertThat(parameters.keySet()).containsExactly("watchRecordId");
        JsonNode watchRecordId = parameters.get("watchRecordId");
        assertThat(watchRecordId.path("in").asText()).isEqualTo("path");
        assertThat(watchRecordId.path("required").asBoolean()).isTrue();
        assertThat(watchRecordId.path("schema").path("type").asText()).isEqualTo("integer");
        assertThat(watchRecordId.path("schema").path("format").asText()).isEqualTo("int64");
        assertThat(watchRecordId.path("schema").path("minimum").asLong()).isEqualTo(1L);

        JsonNode requestBody = operation.path("requestBody");
        assertThat(requestBody.path("required").asBoolean()).isTrue();
        JsonNode request =
                resolve(requestBody.path("content").path("application/json").path("schema"));
        assertThat(values(request.path("required"))).containsExactly("watched");
        assertThat(request.path("properties").path("watched").path("type").asText())
                .isEqualTo("boolean");

        assertResponseSchemas(
                operation.path("responses"),
                Map.of(
                        "200", "WatchConfirmResponse",
                        "400", "ErrorResponse",
                        "401", "ErrorResponse",
                        "404", "ErrorResponse",
                        "409", "ErrorResponse",
                        "415", "ErrorResponse",
                        "500", "ErrorResponse"));
        JsonNode response = schema("WatchConfirmResponse");
        assertThat(response.path("properties").properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("watchRecordId", "status", "watchedAt");
        assertThat(values(response.path("required")))
                .containsExactlyInAnyOrder("watchRecordId", "status", "watchedAt");
        assertThat(values(response.path("properties").path("status").path("enum")))
                .containsExactlyInAnyOrder("WATCHED_UNRATED", "EXPIRED");
        JsonNode watchedAt = response.path("properties").path("watchedAt");
        assertThat(watchedAt.path("format").asText()).isEqualTo("date-time");
        assertThat(values(watchedAt.path("type"))).contains("string", "null");
        assertThat(watchedAt.path("description").asText()).contains("UTC");
    }

    private void assertBearer(JsonNode operation) throws Exception {
        assertThat(operation.path("security"))
                .isEqualTo(objectMapper.readTree("[{\"bearerAuth\":[]}]"));
    }

    private void assertResponseSchemas(JsonNode responses, Map<String, String> expected) {
        expected.forEach(
                (code, schema) -> {
                    assertThat(responses.path(code).path("description").asText()).isNotBlank();
                    assertThat(
                                    responses
                                            .path(code)
                                            .path("content")
                                            .path("application/json")
                                            .path("schema")
                                            .path("$ref")
                                            .asText())
                            .isEqualTo("#/components/schemas/" + schema);
                });
    }

    private Map<String, JsonNode> parameters(JsonNode operation) {
        return StreamSupport.stream(operation.path("parameters").spliterator(), false)
                .collect(
                        java.util.stream.Collectors.toMap(
                                parameter -> parameter.path("name").asText(),
                                parameter -> parameter));
    }

    private JsonNode resolve(JsonNode schema) throws Exception {
        String reference = schema.path("$ref").asText();
        if (reference.isEmpty()) {
            return schema;
        }
        return document()
                .path("components")
                .path("schemas")
                .path(reference.substring(reference.lastIndexOf('/') + 1));
    }

    private JsonNode schema(String name) throws Exception {
        return document().path("components").path("schemas").path(name);
    }

    private JsonNode operation(String path, String method) throws Exception {
        return document().path("paths").path(path).path(method);
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
