package com.feelm.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
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
class OpenApiContractTest {

    private static final String API_DOCS_PATH = "/v3/api-docs";
    private static final String SWAGGER_CONFIG_PATH = "/v3/api-docs/swagger-config";
    private static final String SWAGGER_UI_PATH = "/swagger-ui/index.html";
    private static final String MEMBER_PATH = "/api/v1/test/security/me";
    private static final String REFRESH_PATH = "/api/v1/auth/token/refresh";
    private static final String PROTECTED_PATH = "/api/v1/parties";

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
