package com.feelm.backend.domain.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

@DisplayName("API-028 영화 검색 — 공통 공개 경로 판정 계약")
class MovieSearchPublicPathContractTest {

    private static final String PATH = "/api/v1/movies/search";

    @Test
    @DisplayName("정확한 GET 검색 경로도 회원 인증이 필요하다")
    void getSearchRequiresAuthentication() {
        assertThat(SecurityConfig.isPublicApi(HttpMethod.GET, PATH)).isFalse();
    }

    @ParameterizedTest(name = "{0} 검색 경로는 공개하지 않는다")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE"})
    @DisplayName("GET 이외 메서드는 같은 경로라도 자동 공개하지 않는다")
    void otherMethodsAreNotPublic(String method) {
        assertThat(SecurityConfig.isPublicApi(HttpMethod.valueOf(method), PATH)).isFalse();
    }
}
