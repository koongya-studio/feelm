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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** API-023: 실제 마이그레이션과 PostgreSQL을 사용하는 초기 취향 카드 목록 HTTP 계약 테스트. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@DisplayName("API-023 초기 취향 카드 목록")
class OnboardingCardApiContractTest extends SharedPostgresContractTest {

    private static final String CARDS_PATH = "/api/v1/onboarding/cards";

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
    @DisplayName("T1 계약이 정한 필드를 채운 카드를 기본 20장 내려준다")
    void returnsCardsWithContractFields() throws Exception {
        seedMovies(5);
        OnboardingFixture.genre(jdbc, 1, "ACTION", "액션", 28);
        OnboardingFixture.genre(jdbc, 2, "SF", "SF", 878);
        OnboardingFixture.movieGenre(jdbc, 101, 1);
        OnboardingFixture.movieGenre(jdbc, 101, 2);

        mvc.perform(get(CARDS_PATH).header("Authorization", bearer(ACTIVE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[0].movieId").value(101))
                .andExpect(jsonPath("$.items[0].title").value("영화101"))
                .andExpect(
                        jsonPath("$.items[0].posterUrl")
                                .value("https://image.example.test/101.jpg"))
                .andExpect(jsonPath("$.items[0].releaseYear").value(1999))
                .andExpect(jsonPath("$.items[0].genres[0]").value("액션"))
                .andExpect(jsonPath("$.items[0].genres[1]").value("SF"))
                .andExpect(jsonPath("$.items[0].flavorCode").value(FLAVOR_CODES[0]));
    }

    @Test
    @DisplayName("T2 여덟 개 맛에 카드를 고르게 나눠 담는다")
    void spreadsCardsAcrossFlavors() throws Exception {
        seedMovies(5);

        List<String> flavorCodes = flavorCodesOf(request(ACTIVE, "?size=16"));

        assertThat(flavorCodes).hasSize(16);
        for (String code : FLAVOR_CODES) {
            assertThat(flavorCodes.stream().filter(code::equals).count()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("T3 어떤 맛의 후보가 모자라면 남은 자리를 다른 맛이 채운다")
    void fillsRemainderWhenFlavorIsShort() throws Exception {
        OnboardingFixture.movie(jdbc, 101, 1L, 999);
        for (long flavorId = 2; flavorId <= 8; flavorId++) {
            for (int index = 1; index <= 5; index++) {
                OnboardingFixture.movie(jdbc, flavorId * 100 + index, flavorId, 1000 - index);
            }
        }

        List<String> flavorCodes = flavorCodesOf(request(ACTIVE, "?size=16"));

        assertThat(flavorCodes).hasSize(16);
        assertThat(flavorCodes.stream().filter(FLAVOR_CODES[0]::equals).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("T4 대표 맛이 배정되지 않은 영화는 카드가 되지 않는다")
    void excludesMoviesWithoutFlavor() throws Exception {
        seedMovies(1);
        OnboardingFixture.movie(jdbc, 900, null, 100000);

        List<Long> movieIds = movieIdsOf(request(ACTIVE, ""));

        assertThat(movieIds).hasSize(8).doesNotContain(900L);
    }

    @Test
    @DisplayName("T5 size가 허용 범위를 벗어나면 거절한다")
    void rejectsSizeOutOfRange() throws Exception {
        seedMovies(5);

        mvc.perform(get(CARDS_PATH + "?size=0").header("Authorization", bearer(ACTIVE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get(CARDS_PATH + "?size=41").header("Authorization", bearer(ACTIVE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("T6 이미 온보딩을 마친 사용자는 카드를 받지 못한다")
    void rejectsCompletedUser() throws Exception {
        seedMovies(5);

        mvc.perform(get(CARDS_PATH).header("Authorization", bearer(COMPLETED)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ONBOARDING_ALREADY_COMPLETED"));
    }

    @Test
    @DisplayName("T7 활성 계정이 아니면 카드를 받지 못한다")
    void rejectsInactiveUser() throws Exception {
        seedMovies(5);

        mvc.perform(get(CARDS_PATH).header("Authorization", bearer(WITHDRAWN)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("T8 같은 요청을 반복해도 카드 순서가 같다")
    void returnsSameOrderOnRepeat() throws Exception {
        seedMovies(5);

        assertThat(movieIdsOf(request(ACTIVE, "?size=24")))
                .isEqualTo(movieIdsOf(request(ACTIVE, "?size=24")));
    }

    @Test
    @DisplayName("T9 인증 없이 카드를 받을 수 없다")
    void requiresAuthentication() throws Exception {
        seedMovies(5);

        mvc.perform(get(CARDS_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get(CARDS_PATH).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    /** 맛마다 {@code perFlavor}편을 넣는다. 식별자가 작을수록 평가 수가 많아 인기 순위가 앞선다. */
    private void seedMovies(int perFlavor) {
        for (long flavorId = 1; flavorId <= FLAVOR_CODES.length; flavorId++) {
            for (int index = 1; index <= perFlavor; index++) {
                OnboardingFixture.movie(jdbc, flavorId * 100 + index, flavorId, 1000 - index);
            }
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
