package com.feelm.backend.domain.search;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.search.dto.response.MovieFilterOptionsResponse.Option;
import com.feelm.backend.domain.search.dto.response.MovieFilterOptionsResponse.OttOption;
import com.feelm.backend.domain.search.dto.response.MovieFilterOptionsResponse.YearRange;
import com.feelm.backend.domain.search.repository.MovieFilterOptionsRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "feelm.auth.mock.enabled=false")
@AutoConfigureMockMvc
class MovieFilterOptionsSecurityTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MovieFilterOptionsRepository repository;

    @BeforeEach
    void options() {
        when(repository.findGenres()).thenReturn(List.of(new Option("THRILLER", "스릴러")));
        when(repository.findCountryCodes()).thenReturn(List.of("FR", "KR", "US"));
        when(repository.findYearRange()).thenReturn(new YearRange(1920, 2026));
        when(repository.findOtts()).thenReturn(List.of(new OttOption(1L, "NETFLIX", "넷플릭스")));
    }

    @Test
    void anonymousGetIsPublicAndCachedForOneDay() throws Exception {
        mockMvc.perform(get("/api/v1/movies/filter-options"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=86400"));
    }

    @Test
    void responseHasExactlyTheContractFieldsAndKoreanNames() throws Exception {
        mockMvc.perform(get("/api/v1/movies/filter-options"))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .json(
                                        """
                        {"genres":[{"code":"THRILLER","name":"스릴러"}],
                         "countries":[{"code":"FR","name":"프랑스"},
                                      {"code":"KR","name":"한국"},
                                      {"code":"US","name":"미국"}],
                         "yearRange":{"min":1920,"max":2026},
                         "otts":[{"ottId":1,"code":"NETFLIX","name":"넷플릭스"}]}
                        """,
                                        JsonCompareMode.STRICT));
    }

    @Test
    void emptyResponseKeepsNullableYearFields() throws Exception {
        when(repository.findGenres()).thenReturn(List.of());
        when(repository.findCountryCodes()).thenReturn(List.of());
        when(repository.findYearRange()).thenReturn(new YearRange(null, null));
        when(repository.findOtts()).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/movies/filter-options"))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .json(
                                        """
                        {"genres":[],"countries":[],"yearRange":{"min":null,"max":null},"otts":[]}
                        """,
                                        JsonCompareMode.STRICT));
    }

    @Test
    void unknownCountryReturnsUncacheableInternalErrorWithoutLeakingDetails() throws Exception {
        when(repository.findCountryCodes()).thenReturn(List.of("KR", "ZZ"));

        mockMvc.perform(get("/api/v1/movies/filter-options"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", not(containsString("public"))))
                .andExpect(header().string("Cache-Control", not(containsString("max-age=86400"))))
                .andExpect(
                        content()
                                .json(
                                        """
                                        {"code":"INTERNAL_ERROR","message":"서버 내부 오류가 발생했습니다."}
                                        """,
                                        JsonCompareMode.STRICT));
    }

    @Test
    void existingSearchGetRemainsMemberOnly() throws Exception {
        mockMvc.perform(get("/api/v1/movies/search").param("q", "기생충"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"})
    void otherMethodsStillRequireAuthentication(String method) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(method), "/api/v1/movies/filter-options"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/movies/999", "/api/v1/movies/filter-options/extra"})
    void otherMoviePathsStillRequireAuthentication(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
}
