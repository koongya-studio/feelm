package com.feelm.backend.domain.onboarding;

import static com.feelm.backend.domain.onboarding.OnboardingFixture.ACTIVE;
import static com.feelm.backend.domain.onboarding.OnboardingFixture.COMPLETED;
import static com.feelm.backend.domain.onboarding.OnboardingFixture.FLAVOR_CODES;
import static com.feelm.backend.domain.onboarding.OnboardingFixture.WITHDRAWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** API-023: 실제 마이그레이션과 PostgreSQL을 사용하는 초기 취향 카드 목록 HTTP 계약 테스트. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@DisplayName("API-023 초기 취향 카드 목록")
class OnboardingCardApiContractTest extends SharedPostgresContractTest {

    private static final String CARDS_PATH = "/api/v1/onboarding/cards";
    private static final List<Long> CURATED_MOVIE_IDS =
            List.of(59738L, 69823L, 72564L, 59664L, 45504L, 123L, 76119L, 88608L, 100L, 58868L);
    private static final List<Long> CURATED_FLAVOR_IDS =
            List.of(1L, 2L, 3L, 3L, 4L, 5L, 6L, 7L, 7L, 8L);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void fixtures() {
        OnboardingFixture.reset(jdbc);
        OnboardingFixture.flavors(jdbc);
    }

    @Test
    @DisplayName("T1 확정 영화 10편을 기획 순서와 계약 필드대로 내려준다")
    void returnsCuratedCardsWithContractFields() throws Exception {
        seedCuratedMovies();
        OnboardingFixture.genre(jdbc, 1, "DOCUMENTARY", "다큐멘터리", 99);
        OnboardingFixture.genre(jdbc, 2, "FAMILY", "가족", 10751);
        OnboardingFixture.movieGenre(jdbc, 59738, 1);
        OnboardingFixture.movieGenre(jdbc, 59738, 2);

        MvcResult result = request(ACTIVE, "");

        assertThat(movieIdsOf(result)).containsExactlyElementsOf(CURATED_MOVIE_IDS);
        mvc.perform(get(CARDS_PATH).header("Authorization", bearer(ACTIVE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[0].movieId").value(59738))
                .andExpect(jsonPath("$.items[0].title").value("영화59738"))
                .andExpect(
                        jsonPath("$.items[0].posterUrl")
                                .value("https://image.example.test/59738.jpg"))
                .andExpect(jsonPath("$.items[0].releaseYear").value(1999))
                .andExpect(jsonPath("$.items[0].genres[0]").value("다큐멘터리"))
                .andExpect(jsonPath("$.items[0].genres[1]").value("가족"))
                .andExpect(jsonPath("$.items[0].flavorCode").value("FLAVOR_A"));
    }

    @Test
    @DisplayName("T2 확정 목록이 여덟 개 팝콘 맛을 모두 포함한다")
    void coversAllPopcornFlavors() throws Exception {
        seedCuratedMovies();

        List<String> flavorCodes = flavorCodesOf(request(ACTIVE, ""));

        assertThat(flavorCodes)
                .containsExactly(
                        "FLAVOR_A",
                        "FLAVOR_B",
                        "FLAVOR_C",
                        "FLAVOR_C",
                        "FLAVOR_D",
                        "FLAVOR_E",
                        "FLAVOR_F",
                        "FLAVOR_G",
                        "FLAVOR_G",
                        "FLAVOR_H")
                .contains(FLAVOR_CODES);
    }

    @Test
    @DisplayName("T3 확정 목록 밖의 인기 영화로 카드를 바꾸지 않는다")
    void excludesUncuratedPopularMovies() throws Exception {
        seedCuratedMovies();
        OnboardingFixture.movie(jdbc, 999999, 1L, Integer.MAX_VALUE);

        assertThat(movieIdsOf(request(ACTIVE, "")))
                .containsExactlyElementsOf(CURATED_MOVIE_IDS)
                .doesNotContain(999999L);
    }

    @Test
    @DisplayName("T4 확정 영화의 대표 맛이 없으면 다른 영화로 대체하지 않는다")
    void doesNotReplaceCuratedMovieWithoutFlavor() throws Exception {
        seedCuratedMoviesExcept(59738L);
        OnboardingFixture.movie(jdbc, 59738, null, 1000);
        OnboardingFixture.movie(jdbc, 999999, 1L, Integer.MAX_VALUE);

        assertThat(movieIdsOf(request(ACTIVE, "")))
                .hasSize(9)
                .doesNotContain(59738L, 999999L)
                .containsExactlyElementsOf(CURATED_MOVIE_IDS.subList(1, CURATED_MOVIE_IDS.size()));
    }

    @Test
    @DisplayName("T5 size가 1~10을 벗어나면 거절한다")
    void rejectsSizeOutOfRange() throws Exception {
        seedCuratedMovies();

        mvc.perform(get(CARDS_PATH + "?size=0").header("Authorization", bearer(ACTIVE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get(CARDS_PATH + "?size=11").header("Authorization", bearer(ACTIVE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("T6 이미 온보딩을 마친 사용자는 카드를 받지 못한다")
    void rejectsCompletedUser() throws Exception {
        seedCuratedMovies();

        mvc.perform(get(CARDS_PATH).header("Authorization", bearer(COMPLETED)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ONBOARDING_ALREADY_COMPLETED"));
    }

    @Test
    @DisplayName("T7 활성 계정이 아니면 카드를 받지 못한다")
    void rejectsInactiveUser() throws Exception {
        seedCuratedMovies();

        mvc.perform(get(CARDS_PATH).header("Authorization", bearer(WITHDRAWN)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("T8 같은 요청을 반복해도 확정 카드 순서가 같다")
    void returnsSameOrderOnRepeat() throws Exception {
        seedCuratedMovies();

        assertThat(movieIdsOf(request(ACTIVE, "?size=10")))
                .isEqualTo(movieIdsOf(request(ACTIVE, "?size=10")))
                .containsExactlyElementsOf(CURATED_MOVIE_IDS);
    }

    @Test
    @DisplayName("T9 인증 없이 카드를 받을 수 없다")
    void requiresAuthentication() throws Exception {
        seedCuratedMovies();

        mvc.perform(get(CARDS_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get(CARDS_PATH).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private void seedCuratedMovies() {
        seedCuratedMoviesExcept(null);
    }

    private void seedCuratedMoviesExcept(Long excludedMovieId) {
        for (int index = 0; index < CURATED_MOVIE_IDS.size(); index++) {
            long movieId = CURATED_MOVIE_IDS.get(index);
            if (Long.valueOf(movieId).equals(excludedMovieId)) {
                continue;
            }
            OnboardingFixture.movie(jdbc, movieId, CURATED_FLAVOR_IDS.get(index), 1000 - index);
        }
    }

    private MvcResult request(long userId, String query) throws Exception {
        return mvc.perform(get(CARDS_PATH + query).header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private List<Long> movieIdsOf(MvcResult result) throws Exception {
        return StreamSupport.stream(items(result).spliterator(), false)
                .map(item -> item.get("movieId").asLong())
                .toList();
    }

    private List<String> flavorCodesOf(MvcResult result) throws Exception {
        return StreamSupport.stream(items(result).spliterator(), false)
                .map(item -> item.get("flavorCode").asText())
                .toList();
    }

    private JsonNode items(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("items");
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }
}
