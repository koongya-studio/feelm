package com.feelm.backend.domain.movie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.error.GlobalExceptionHandler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 계획에 명시된 Controller/Service 경계만 고정하며 구현 전에도 컴파일 가능하게 한다. */
@DisplayName("API-030 Controller — 선택 인증과 추천 ID 전달")
class MovieDetailControllerTest {

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @MethodSource("requests")
    @DisplayName("영화 ID와 nullable 추천 ID·회원 ID를 조회 Service에 한 번 전달한다")
    void forwardsOptionalArguments(long movieId, Long recommendationItemId, Long userId)
            throws Exception {
        SecurityContextHolder.clearContext();
        Class<?> controllerType = movieType("controller.MovieDetailController");
        Class<?> serviceType = movieType("service.MovieDetailService");
        Method detailMethod =
                serviceType.getMethod("getDetail", long.class, Long.class, Long.class);
        Object service = mock(serviceType);
        Object controller = controllerType.getConstructor(serviceType).newInstance(service);
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        if (userId != null) {
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        }
        MockHttpServletRequestBuilder request = get("/api/v1/movies/{movieId}", movieId);
        if (recommendationItemId != null) {
            request.param("recommendationItemId", recommendationItemId.toString());
        }
        mvc.perform(request).andExpect(status().isOk());
        assertThat(mockingDetails(service).getInvocations())
                .filteredOn(invocation -> invocation.getMethod().equals(detailMethod))
                .singleElement()
                .satisfies(
                        invocation ->
                                assertThat(invocation.getArguments())
                                        .containsExactly(movieId, recommendationItemId, userId));
    }

    static Stream<Arguments> requests() {
        return Stream.of(
                Arguments.of(550L, null, null),
                Arguments.of(550L, 3301L, null),
                Arguments.of(550L, null, 7L),
                Arguments.of(550L, 3301L, 7L),
                Arguments.of(
                        9_007_199_254_740_993L, 9_007_199_254_740_995L, 9_007_199_254_740_997L));
    }

    private static Class<?> movieType(String name) {
        try {
            return Class.forName("com.feelm.backend.domain.movie." + name);
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("API-030 구현 타입이 아직 없습니다: " + name, exception);
        }
    }
}
