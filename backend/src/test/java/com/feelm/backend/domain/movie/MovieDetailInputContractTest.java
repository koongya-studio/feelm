package com.feelm.backend.domain.movie;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.movie.controller.MovieDetailController;
import com.feelm.backend.domain.movie.service.MovieDetailService;
import com.feelm.backend.global.error.GlobalExceptionHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Spring의 기본 Long 변환이 형식 오류를 정상 ID로 보정하지 않도록 HTTP 경계를 검증한다. */
class MovieDetailInputContractTest {

    private final MovieDetailService service = mock(MovieDetailService.class);
    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new MovieDetailController(service))
                    .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();

    @ParameterizedTest
    @ValueSource(strings = {"5 5 0", " 550", "550 ", "0x226", "#226"})
    void malformedMovieIdNeverReachesService(String movieId) throws Exception {
        mvc.perform(get("/api/v1/movies/{movieId}", movieId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3 3 0 1", " 3301", "3301 ", "0xce5", "#ce5"})
    void malformedRecommendationItemIdNeverReachesService(String itemId) throws Exception {
        mvc.perform(get("/api/v1/movies/550").param("recommendationItemId", itemId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }
}
