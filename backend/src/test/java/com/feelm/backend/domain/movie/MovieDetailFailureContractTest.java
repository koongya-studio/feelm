package com.feelm.backend.domain.movie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.movie.repository.MovieDetailRepository;
import com.feelm.backend.domain.movie.repository.MovieDetailRepository.MovieMetadata;
import com.feelm.backend.domain.movie.repository.MovieDetailRepository.Personalization;
import com.feelm.backend.domain.movie.repository.MovieDetailRepository.RecommendationSnapshot;
import com.feelm.backend.global.security.TokenProvider;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** JSONB는 문법 오류를 저장할 수 없으므로 이 실패 경로만 Repository projection 대역으로 재현한다. */
@SpringBootTest(properties = "feelm.auth.mock.enabled=false")
@AutoConfigureMockMvc
class MovieDetailFailureContractTest {

    @Autowired private MockMvc mvc;
    @Autowired private TokenProvider tokens;
    @MockitoBean private MovieDetailRepository repository;

    @BeforeEach
    void movieExists() {
        when(repository.findMovie(550L))
                .thenReturn(
                        Optional.of(
                                new MovieMetadata(
                                        550L,
                                        "테스트",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        BigDecimal.ZERO,
                                        0)));
        when(repository.findPersonalization(550L, 7L)).thenReturn(new Personalization(null, null));
    }

    @Test
    void malformedReasonUsesCommonErrorResponse() throws Exception {
        when(repository.findRecommendation(550L, 3301L, 7L))
                .thenReturn(
                        Optional.of(
                                new RecommendationSnapshot(
                                        3301L, "TASTE", "{internal-reason", 7L, false)));
        String body =
                mvc.perform(
                                get("/api/v1/movies/550")
                                        .param("recommendationItemId", "3301")
                                        .header(
                                                "Authorization",
                                                "Bearer " + tokens.createAccessToken(7L)))
                        .andExpect(status().isInternalServerError())
                        .andExpect(content().contentTypeCompatibleWith("application/json"))
                        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).doesNotContain("internal-reason", "Exception", "reason");
    }

    @Test
    void inaccessibleReasonIsNotParsed() throws Exception {
        when(repository.findRecommendation(550L, 3301L, 7L))
                .thenReturn(
                        Optional.of(
                                new RecommendationSnapshot(
                                        3301L, "TASTE", "{internal-reason", 8L, false)));
        mvc.perform(
                        get("/api/v1/movies/550")
                                .param("recommendationItemId", "3301")
                                .header("Authorization", "Bearer " + tokens.createAccessToken(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendation").value(org.hamcrest.Matchers.nullValue()));
    }
}
