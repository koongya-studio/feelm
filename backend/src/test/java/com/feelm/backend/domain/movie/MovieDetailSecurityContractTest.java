package com.feelm.backend.domain.movie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.SecurityConfig;
import com.feelm.backend.support.SharedTestRuntime;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "feelm.auth.mock.enabled=false")
@AutoConfigureMockMvc
@SharedTestRuntime
class MovieDetailSecurityContractTest {

    @Autowired private MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE"})
    void detailIsPublicForGetOnly(String method) throws Exception {
        HttpMethod httpMethod = HttpMethod.valueOf(method);
        assertThat(SecurityConfig.isPublicApi(httpMethod, "/api/v1/movies/{movieId}")).isFalse();
        // TRACE는 기존 StrictHttpFirewall이 인증 단계 전에 400으로 거절한다.
        mvc.perform(request(httpMethod, "/api/v1/movies/550"))
                .andExpect(status().is(method.equals("TRACE") ? 400 : 401));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/movies/search", "/api/v1/movies/%73earch"})
    void searchRemainsMemberOnly(String path) throws Exception {
        mvc.perform(get(URI.create(path)).param("q", "test"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
    void invalidAnonymousMovieIdsReachValidation(String movieId) throws Exception {
        mvc.perform(get("/api/v1/movies/{movieId}", movieId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void contextPathKeepsTheSameSecurityBoundary() throws Exception {
        mvc.perform(get("/feelm/api/v1/movies/0").contextPath("/feelm"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/feelm/api/v1/movies/search").contextPath("/feelm").param("q", "test"))
                .andExpect(status().isUnauthorized());
    }
}
