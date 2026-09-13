package com.feelm.backend.domain.search;

import static com.feelm.backend.domain.search.SearchUnitTestReflection.searchType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.search.dto.response.MovieSearchPageResponse;
import com.feelm.backend.global.error.GlobalExceptionHandler;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 실제 Controller와 principal resolver만 실행하고 Service를 mock으로 격리하는 단위 테스트.
 *
 * <p>보안 필터를 실행하지 않으므로 null 바인딩 검사는 비회원 접근 허용을 의미하지 않는다. 실제 인증·인가는 MovieSearchContractTest에서 검증한다.
 */
@DisplayName("MovieSearchController — Service에 인증 사용자 ID 전달")
class MovieSearchControllerTest {

    private Class<?> criteriaType;
    private Method searchMethod;
    private Object service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        SecurityContextHolder.clearContext();
        Class<?> controllerType = searchType("controller.MovieSearchController");
        Class<?> serviceType = searchType("service.MovieSearchService");
        criteriaType = searchType("dto.request.MovieSearchCriteria");
        searchMethod = serviceType.getMethod("search", criteriaType, Long.class);
        MovieSearchPageResponse response = new MovieSearchPageResponse(0L, List.of(), null, false);
        service =
                mock(
                        serviceType,
                        invocation -> {
                            if (invocation.getMethod().equals(searchMethod)) {
                                return response;
                            }
                            return Answers.RETURNS_DEFAULTS.answer(invocation);
                        });
        Object controller = controllerType.getConstructor(serviceType).newInstance(service);
        mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "principal userId={0}")
    @NullSource
    @ValueSource(longs = {7L, 91002L, 9007199254740993L})
    @DisplayName("principal 바인딩 결과인 Long ID 또는 null을 Service에 한 번 그대로 전달한다")
    void forwardsPrincipalIdToService(Long userId) throws Exception {
        if (userId != null) {
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        }

        mockMvc.perform(get("/api/v1/movies/search").param("q", "기생충"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0L))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));

        // 반환값으로 간접 추측하지 않고 실제 mock 호출의 인자를 검증한다.
        assertThat(mockingDetails(service).getInvocations())
                .filteredOn(invocation -> invocation.getMethod().equals(searchMethod))
                .singleElement()
                .satisfies(
                        invocation -> {
                            Object[] arguments = invocation.getArguments();
                            assertThat(arguments).hasSize(2);
                            assertThat(arguments[0]).isInstanceOf(criteriaType);
                            assertThat(arguments[1]).isEqualTo(userId);
                        });
    }
}
