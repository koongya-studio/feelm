package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class SimilarMoviePublicPathTest {

    @Test
    void opensOnlyApi031GetShape() {
        assertThat(SecurityConfig.isPublicApi(HttpMethod.GET, "/api/v1/movies/100/similar"))
                .isTrue();
        assertThat(SecurityConfig.isPublicApi(HttpMethod.POST, "/api/v1/movies/100/similar"))
                .isFalse();
        assertThat(SecurityConfig.isPublicApi(HttpMethod.GET, "/api/v1/movies/100/similar/extra"))
                .isFalse();
        assertThat(SecurityConfig.isPublicApi(HttpMethod.GET, "/api/v1/movies/search")).isFalse();
        assertThat(SecurityConfig.isPublicApi(HttpMethod.GET, "/api/v1/movies/filter-options"))
                .isTrue();
    }
}
