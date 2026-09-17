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
@DisplayName("평가 수정·삭제 OpenAPI 계약 — API-037·038")
class RatingMutationOpenApiContractTest {

    private static final String PATH = "/api/v1/ratings/{ratingId}";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("PUT은 인증·요청 필드·200 및 오류 스키마를 문서화한다")
    void documentsUpdateDelete() throws Exception {
        JsonNode document = document();
        JsonNode path = document.path("paths").path(PATH);
        JsonNode update = path.path("put");
        JsonNode deletion = path.path("delete");
        assertThat(update.isMissingNode()).isFalse();
        assertThat(deletion.isMissingNode()).isFalse();

        for (JsonNode operation : List.of(update, deletion)) {
            assertThat(operation.path("security"))
                    .isEqualTo(objectMapper.readTree("[{\"bearerAuth\":[]}]"));
            JsonNode id = operation.path("parameters").path(0);
            assertThat(id.path("name").asText()).isEqualTo("ratingId");
            assertThat(id.path("in").asText()).isEqualTo("path");
            assertThat(id.path("required").asBoolean()).isTrue();
            assertThat(id.path("schema").path("minimum").asLong()).isEqualTo(1);
        }

        JsonNode request =
                resolve(
                        document,
                        update.path("requestBody")
                                .path("content")
                                .path("application/json")
                                .path("schema"));
        assertThat(request.path("properties").properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("score", "comment", "visibility", "spoiler");
        assertThat(values(request.path("required"))).contains("score").doesNotContain("spoiler");
        JsonNode score = request.path("properties").path("score");
        assertThat(score.path("minimum").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(score.path("maximum").decimalValue()).isEqualByComparingTo("5.0");
        assertThat(score.path("multipleOf").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(request.path("properties").path("comment").path("maxLength").asInt())
                .isEqualTo(200);
        assertThat(values(request.path("properties").path("visibility").path("enum")))
                .containsExactlyInAnyOrder("PUBLIC", "PRIVATE");
        JsonNode spoiler = request.path("properties").path("spoiler");
        assertThat(spoiler.path("type").asText()).isEqualTo("boolean");
        assertThat(spoiler.path("default").asBoolean()).isFalse();

        JsonNode updateResponses = update.path("responses");
        assertThat(updateResponses.properties())
                .extracting(Map.Entry::getKey)
                .contains("200", "400", "401", "404", "415", "500");
        assertSchema(updateResponses, "200", "RatingCreateResponse");
        for (String code : List.of("400", "401", "404", "415", "500")) {
            assertSchema(updateResponses, code, "ErrorResponse");
        }

        JsonNode deleteResponses = deletion.path("responses");
        assertThat(deleteResponses.properties())
                .extracting(Map.Entry::getKey)
                .contains("204", "400", "401", "404", "500");
        assertThat(deleteResponses.path("204").path("content").isMissingNode()).isTrue();
        for (String code : List.of("400", "401", "404", "500")) {
            assertSchema(deleteResponses, code, "ErrorResponse");
        }
    }

    private void assertSchema(JsonNode responses, String code, String schema) {
        assertThat(
                        responses
                                .path(code)
                                .path("content")
                                .path("application/json")
                                .path("schema")
                                .path("$ref")
                                .asText())
                .isEqualTo("#/components/schemas/" + schema);
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
