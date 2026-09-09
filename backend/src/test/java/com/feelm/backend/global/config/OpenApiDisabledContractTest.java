package com.feelm.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.feelm.backend.global.security.TokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
        properties = {
            "springdoc.api-docs.enabled=false",
            "springdoc.swagger-ui.enabled=false",
            "feelm.auth.mock.enabled=false"
        })
@AutoConfigureMockMvc
@DisplayName("Swagger 비활성 환경 — 문서 미노출")
class OpenApiDisabledContractTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TokenProvider tokenProvider;

    @Test
    @DisplayName("인증 여부와 관계없이 UI와 원본 명세가 존재하지 않는다")
    void documentationIsNotExposedEvenWithValidJwt() throws Exception {
        String accessToken = tokenProvider.createAccessToken(223L);

        for (String path :
                new String[] {
                    "/swagger-ui.html",
                    "/swagger-ui/index.html",
                    "/v3/api-docs",
                    "/v3/api-docs.yaml",
                    "/v3/api-docs/swagger-config"
                }) {
            assertDocumentationIsNotExposed(mockMvc.perform(get(path)).andReturn());
            assertDocumentationIsNotExposed(
                    mockMvc.perform(get(path).header("Authorization", "Bearer " + accessToken))
                            .andReturn());
        }
    }

    private void assertDocumentationIsNotExposed(MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        assertThat(status < 200 || status >= 300).isTrue();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("\"openapi\"")
                .doesNotContain("Swagger UI");
    }
}
