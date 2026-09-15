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

/** 어노테이션 존재가 아니라 실제 springdoc JSON으로 API-033 계약을 고정한다. */
@SpringBootTest(
        properties = {
            "springdoc.api-docs.enabled=true",
            "springdoc.swagger-ui.enabled=true",
            "feelm.auth.mock.enabled=false"
        })
@AutoConfigureMockMvc
@SharedTestRuntime
@DisplayName("OTT 클릭 OpenAPI 계약 — API-033")
class OttClickOpenApiContractTest {

    private static final String PATH = "/api/v1/movies/{movieId}/ott-clicks";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("경로·요청 본문·Bearer 인증을 문서화한다")
    void describesPathBodyAndBearerSecurity() throws Exception {
        JsonNode operation = operation();

        assertThat(operation.path("summary").asText()).isEqualTo("OTT 링크 클릭 기록");
        assertThat(operation.path("description").asText())
                .contains("LINK_CLICKED", "OTT_CLICK", "활성 감상 기록");
        assertThat(operation.path("security"))
                .isEqualTo(objectMapper.readTree("[{\"bearerAuth\":[]}]"));
        List<JsonNode> parameters =
                StreamSupport.stream(operation.path("parameters").spliterator(), false).toList();
        assertThat(parameters).hasSize(1);
        JsonNode movieId = parameters.getFirst();
        assertThat(movieId.path("name").asText()).isEqualTo("movieId");
        assertThat(movieId.path("in").asText()).isEqualTo("path");
        assertThat(movieId.path("required").asBoolean()).isTrue();
        assertThat(movieId.path("description").asText()).contains("양의 64비트 정수");
        assertThat(movieId.path("schema").path("type").asText()).isEqualTo("integer");
        assertThat(movieId.path("schema").path("format").asText()).isEqualTo("int64");
        assertThat(movieId.path("schema").path("minimum").asLong()).isEqualTo(1L);

        JsonNode requestBody = operation.path("requestBody");
        assertThat(requestBody.path("required").asBoolean()).isTrue();
        assertThat(
                        requestBody
                                .path("content")
                                .path("application/json")
                                .path("schema")
                                .path("$ref")
                                .asText())
                .isEqualTo("#/components/schemas/OttClickRequest");
        JsonNode requestSchema =
                document().path("components").path("schemas").path("OttClickRequest");
        assertThat(required(requestSchema)).containsExactly("ottId");
        JsonNode ottId = requestSchema.path("properties").path("ottId");
        assertThat(ottId.path("type").asText()).isEqualTo("integer");
        assertThat(ottId.path("format").asText()).isEqualTo("int64");
        assertThat(ottId.path("minimum").asLong()).isEqualTo(1L);
        assertThat(ottId.path("description").asText()).contains("영화에 연결된 OTT");
    }

    @Test
    @DisplayName("신규·기존 성공과 공통 오류 응답 스키마를 문서화한다")
    void describesSuccessAndErrorSchemas() throws Exception {
        JsonNode responses = operation().path("responses");

        assertThat(responses.properties())
                .extracting(Map.Entry::getKey)
                .contains("200", "201", "400", "401", "404", "500");
        for (String code : List.of("200", "201", "400", "401", "404", "500")) {
            JsonNode response = responses.path(code);
            assertThat(response.path("description").asText()).isNotBlank();
            assertThat(
                            response.path("content")
                                    .path("application/json")
                                    .path("schema")
                                    .path("$ref")
                                    .asText())
                    .isEqualTo(
                            "#/components/schemas/"
                                    + (code.equals("200") || code.equals("201")
                                            ? "OttClickResponse"
                                            : "ErrorResponse"));
        }
        assertThat(responses.path("200").path("description").asText()).contains("재클릭", "상태 유지");
        assertThat(responses.path("201").path("description").asText()).contains("LINK_CLICKED");
        assertThat(responses.path("400").path("description").asText())
                .contains("VALIDATION_FAILED");
        assertThat(responses.path("401").path("description").asText()).contains("UNAUTHORIZED");
        assertThat(responses.path("404").path("description").asText())
                .contains("RESOURCE_NOT_FOUND");
        assertThat(responses.path("500").path("description").asText()).contains("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("응답 6개 필드와 상태 enum·nullable UTC 시각을 문서화한다")
    void describesRequiredFieldsEnumsAndUtcTimes() throws Exception {
        JsonNode response = document().path("components").path("schemas").path("OttClickResponse");

        assertThat(response.path("properties").properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "watchRecordId",
                        "movieId",
                        "status",
                        "clickedAt",
                        "confirmDueAt",
                        "expiresAt");
        assertThat(required(response))
                .containsExactlyInAnyOrder(
                        "watchRecordId",
                        "movieId",
                        "status",
                        "clickedAt",
                        "confirmDueAt",
                        "expiresAt");
        JsonNode properties = response.path("properties");
        for (String id : List.of("watchRecordId", "movieId")) {
            assertThat(properties.path(id).path("type").asText()).isEqualTo("integer");
            assertThat(properties.path(id).path("format").asText()).isEqualTo("int64");
            assertThat(properties.path(id).path("description").asText()).isNotBlank();
        }
        assertThat(values(properties.path("status").path("enum")))
                .containsExactlyInAnyOrder("LINK_CLICKED", "WATCHED_UNRATED", "RATED");
        for (String time : List.of("clickedAt", "confirmDueAt", "expiresAt")) {
            JsonNode field = properties.path(time);
            assertThat(field.path("format").asText()).isEqualTo("date-time");
            assertThat(values(field.path("type"))).contains("string", "null");
            assertThat(field.path("description").asText()).contains("UTC");
        }
    }

    private JsonNode operation() throws Exception {
        return document().path("paths").path(PATH).path("post");
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

    private static List<String> required(JsonNode schema) {
        return values(schema.path("required"));
    }

    private static List<String> values(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }
}
