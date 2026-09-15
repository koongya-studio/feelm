package com.feelm.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedTestRuntime;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        properties = {
            "springdoc.api-docs.enabled=true",
            "springdoc.swagger-ui.enabled=true",
            "feelm.auth.mock.enabled=false"
        })
@AutoConfigureMockMvc
@DisplayName("Swagger 활성 환경 — 문서 생성과 기존 JWT 계약")
@SharedTestRuntime
class OpenApiContractTest {

    private static final String API_DOCS_PATH = "/v3/api-docs";
    private static final String SWAGGER_CONFIG_PATH = "/v3/api-docs/swagger-config";
    private static final String SWAGGER_UI_PATH = "/swagger-ui/index.html";
    private static final String MEMBER_PATH = "/api/v1/test/security/me";
    private static final String REFRESH_PATH = "/api/v1/auth/token/refresh";
    private static final String PROTECTED_PATH = "/api/v1/parties";
    private static final String FILTER_OPTIONS_PATH = "/api/v1/movies/filter-options";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TokenProvider tokenProvider;

    @Test
    @DisplayName("OpenAPI JSON은 실제 v1 API와 JWT Bearer 보안 스키마를 생성한다")
    void apiDocsDescribeProductApiAndBearerJwt() throws Exception {
        MvcResult result =
                mockMvc.perform(get(API_DOCS_PATH))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith("application/json"))
                        .andReturn();

        JsonNode document = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(document.path("paths").has(REFRESH_PATH)).isTrue();
        assertThat(document.path("paths").has("/actuator/health")).isFalse();
        assertThat(document.path("paths").has(MEMBER_PATH)).isFalse();

        JsonNode securitySchemes = document.path("components").path("securitySchemes");
        assertThat(securitySchemes.isObject()).isTrue();
        assertThat(
                        StreamSupport.stream(securitySchemes.spliterator(), false)
                                .anyMatch(
                                        scheme ->
                                                "http".equals(scheme.path("type").asText())
                                                        && "bearer"
                                                                .equalsIgnoreCase(
                                                                        scheme.path("scheme")
                                                                                .asText())
                                                        && "JWT"
                                                                .equalsIgnoreCase(
                                                                        scheme.path("bearerFormat")
                                                                                .asText())))
                .isTrue();
        assertThat(
                        document.path("paths")
                                .path(REFRESH_PATH)
                                .path("post")
                                .path("security")
                                .isEmpty())
                .isTrue();
        assertThat(
                        document.path("paths")
                                .path(PROTECTED_PATH)
                                .path("post")
                                .path("security")
                                .findValues(OpenApiConfig.BEARER_AUTH_SCHEME))
                .isNotEmpty();
        assertThat(
                        document.path("paths")
                                .path("/api/v1/movies/search")
                                .path("get")
                                .path("security")
                                .findValues(OpenApiConfig.BEARER_AUTH_SCHEME))
                .isNotEmpty();

        JsonNode movieSearch = document.path("paths").path("/api/v1/movies/search").path("get");
        assertThat(movieSearch.path("summary").asText()).isEqualTo("영화 검색");
        assertThat(movieSearch.path("description").asText()).isNotBlank();
        assertThat(
                        StreamSupport.stream(movieSearch.path("parameters").spliterator(), false)
                                .map(parameter -> parameter.path("name").asText())
                                .toList())
                .containsExactlyInAnyOrder(
                        "q",
                        "genre",
                        "country",
                        "yearFrom",
                        "yearTo",
                        "ott",
                        "sort",
                        "cursor",
                        "size");
        assertThat(
                        StreamSupport.stream(movieSearch.path("parameters").spliterator(), false)
                                .map(parameter -> parameter.path("description").asText())
                                .toList())
                .allMatch(description -> !description.isBlank());
        assertThat(movieSearch.path("responses").has("200")).isTrue();
        assertThat(movieSearch.path("responses").has("400")).isTrue();
        assertThat(movieSearch.path("responses").has("401")).isTrue();

        JsonNode movieSearchSchema =
                document.path("components").path("schemas").path("MovieSearchPageResponse");
        assertThat(movieSearchSchema.path("properties").has("totalCount")).isTrue();
        assertThat(
                        StreamSupport.stream(
                                        movieSearchSchema.path("required").spliterator(), false)
                                .map(JsonNode::asText)
                                .toList())
                .contains("totalCount", "items", "hasNext");
    }

    @Test
    @DisplayName("Swagger UI와 UI 설정 리소스를 활성 환경에서 제공한다")
    void swaggerUiResourcesAreAvailable() throws Exception {
        mockMvc.perform(get(SWAGGER_UI_PATH))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Swagger UI")));

        mockMvc.perform(get(SWAGGER_CONFIG_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test
    @DisplayName("검색 필터 옵션은 공개 API와 확정 응답을 OpenAPI에 설명한다")
    void filterOptionsDescribePublicContract() throws Exception {
        MvcResult result =
                mockMvc.perform(get(API_DOCS_PATH))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith("application/json"))
                        .andReturn();

        JsonNode document = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode operation = document.path("paths").path(FILTER_OPTIONS_PATH).path("get");

        assertThat(operation.path("summary").asText()).isEqualTo("검색 필터 옵션 조회");
        assertThat(operation.path("description").asText()).isNotBlank();
        assertThat(operation.path("security").isEmpty()).isTrue();
        assertThat(operation.path("parameters").isEmpty()).isTrue();
        assertThat(operation.path("responses").has("200")).isTrue();
        assertThat(operation.path("responses").has("500")).isTrue();
        assertThat(operation.path("responses").has("401")).isFalse();
        assertThat(
                        operation
                                .path("responses")
                                .path("200")
                                .path("content")
                                .path("application/json")
                                .path("schema")
                                .path("$ref")
                                .asText())
                .isEqualTo("#/components/schemas/MovieFilterOptionsResponse");

        JsonNode errorContent = operation.path("responses").path("500").path("content");
        assertThat(errorContent.has("application/json")).isTrue();
        assertThat(errorContent.path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/ErrorResponse");

        JsonNode errorSchema = document.path("components").path("schemas").path("ErrorResponse");
        assertThat(errorSchema.path("properties").has("code")).isTrue();
        assertThat(errorSchema.path("properties").has("message")).isTrue();

        JsonNode schema =
                document.path("components").path("schemas").path("MovieFilterOptionsResponse");
        assertThat(schema.path("properties").size()).isEqualTo(4);
        assertThat(schema.path("properties").has("genres")).isTrue();
        assertThat(schema.path("properties").has("countries")).isTrue();
        assertThat(schema.path("properties").has("yearRange")).isTrue();
        assertThat(schema.path("properties").has("otts")).isTrue();
        assertThat(
                        StreamSupport.stream(schema.path("required").spliterator(), false)
                                .map(JsonNode::asText)
                                .toList())
                .containsExactlyInAnyOrder("genres", "countries", "yearRange", "otts");
    }

    @Test
    @DisplayName("Swagger를 켜도 회원 API는 JWT가 필요하고 유효한 JWT는 그대로 허용된다")
    void enablingSwaggerDoesNotReplaceJwtAuthentication() throws Exception {
        mockMvc.perform(get(MEMBER_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        String accessToken = tokenProvider.createAccessToken(223L);
        mockMvc.perform(get(MEMBER_PATH).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(content().string("223"));
    }

    @Test
    @DisplayName("Swagger를 켜도 기존 공개 재발급 경로는 JWT 필터에 가로막히지 않는다")
    void enablingSwaggerKeepsRefreshEndpointPublic() throws Exception {
        mockMvc.perform(post(REFRESH_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }
}
