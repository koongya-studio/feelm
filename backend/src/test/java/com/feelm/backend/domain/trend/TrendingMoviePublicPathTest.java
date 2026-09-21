package com.feelm.backend.domain.trend;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

/** API-064는 공개 GET이며 영화 상세의 선택 인증 경로로 분류되지 않는다. */
@DisplayName("API-064 급상승 영화 — 공통 공개 경로 판정 계약")
class TrendingMoviePublicPathTest {

    private static final String PATH = "/api/v1/movies/trending";

    @Test
    @DisplayName("GET은 공개이고 선택 인증이 아니다")
    void getIsPublicButNotOptionalAuthentication() {
        assertThat(SecurityConfig.isPublicApi(HttpMethod.GET, PATH)).isTrue();
        // {movieId} 템플릿과 문자열이 겹쳐 선택 인증으로 잡히면 OpenAPI가 영화 상세처럼 표시한다.
        assertThat(SecurityConfig.isOptionalAuthenticationApi(HttpMethod.GET, PATH)).isFalse();
        assertThat(SecurityConfig.isPublicApi(HttpMethod.GET, "/api/v1/movies/trending/extra"))
                .isFalse();
    }

    @ParameterizedTest(name = "{0} 급상승 경로는 공개하지 않는다")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE"})
    @DisplayName("GET 이외 메서드는 같은 경로라도 자동 공개하지 않는다")
    void otherMethodsAreNotPublic(String method) {
        assertThat(SecurityConfig.isPublicApi(HttpMethod.valueOf(method), PATH)).isFalse();
    }
}
