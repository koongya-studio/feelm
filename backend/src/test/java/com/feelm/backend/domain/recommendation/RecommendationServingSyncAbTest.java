package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.feelm.backend.domain.recommendation.model.RecommendationCandidate;
import com.feelm.backend.domain.recommendation.model.RecommendationCandidateBatch;
import com.feelm.backend.domain.recommendation.repository.RecommendationCandidateReader;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** S15P21E106-613의 임시 요청 시 동기화 경로가 기존 세트를 안전하게 대체하는지 검증한다. */
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.task.scheduling.enabled=false"
        })
@AutoConfigureMockMvc
@DisplayName("S15P21E106-613 조회 시 임시 동기화")
class RecommendationServingSyncAbTest extends SharedPostgresContractTest {

    private static final long USER = 10;
    private static final long OTHER_USER = 20;
    private static final long SET = 1000;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @MockitoBean RecommendationCandidateReader candidateReader;

    @BeforeEach
    void fixtures() {
        reset(candidateReader);
        when(candidateReader.read(anyLong()))
                .thenThrow(new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE));
        jdbc.execute(
                "TRUNCATE TABLE user_movie_dismissals, ratings, watch_records,"
                        + " movie_ott_providers, recommendation_items, recommendation_sets,"
                        + " movies, users, genres, otts, popcorn_flavors"
                        + " RESTART IDENTITY CASCADE");
        user(USER, "user");
        user(OTHER_USER, "other");
        recommendationSet(SET, USER, null);
        for (long movieId = 101; movieId <= 106; movieId++) {
            movie(movieId);
        }
        item(1001, 101, "TASTE", 1);
        item(1002, 102, "TASTE", 2);
        item(1003, 103, "TASTE", 3);
        item(1004, 104, "TASTE", 4);
        item(1005, 105, "DISCOVERY", 1);
        item(1006, 106, "DISCOVERY", 2);
    }

    @Test
    @DisplayName("Redis 후보가 모두 부적격이어도 기존 세트를 계속 제공한다")
    void servesExistingSetWhenNoCandidateIsEligible() throws Exception {
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (?,104,5)", USER);
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (?,106,5)", USER);
        candidates(
                "generation-all-ineligible",
                candidate(104, "5.0", "TASTE"),
                candidate(106, "3.0", "DISCOVERY"));

        MvcResult result = getRecommendations(USER);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("\"recommendationSetId\":" + SET);
    }

    @Test
    @DisplayName("새 generation이 오면 임시 정책에 따라 최신 세트로 즉시 전환한다")
    void switchesToLatestSetWhenNewGenerationArrives() throws Exception {
        assertThat(getRecommendations(USER).getResponse().getStatus()).isEqualTo(200);
        assertThat(exposedIds()).containsExactly(1001L, 1002L, 1005L);
        candidates(
                "generation-new",
                candidate(103, "5.0", "TASTE"),
                candidate(104, "4.0", "TASTE"),
                candidate(106, "3.0", "DISCOVERY"));

        MvcResult result = getRecommendations(USER);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("\"recommendationSetId\":" + SET)
                .contains("\"movieId\":103")
                .contains("\"movieId\":104")
                .contains("\"movieId\":106");
    }

    @Test
    @DisplayName("같은 generation의 다른 사용자 세트가 있어도 기존 자기 세트로 대체한다")
    void fallsBackWhenGenerationIsHeldByAnotherUser() throws Exception {
        recommendationSet(2000, OTHER_USER, "generation-shared");
        candidates("generation-shared", candidate(103, "5.0", "TASTE"));

        MvcResult result = getRecommendations(USER);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("\"recommendationSetId\":2000")
                .contains("\"recommendationSetId\":" + SET);
    }

    private MvcResult getRecommendations(long userId) throws Exception {
        return mvc.perform(
                        get("/api/v1/recommendations")
                                .header(
                                        "Authorization",
                                        "Bearer " + tokens.createAccessToken(userId)))
                .andReturn();
    }

    private void candidates(String generationId, RecommendationCandidate... items) {
        doReturn(
                        new RecommendationCandidateBatch(
                                "als-ab-example",
                                10L,
                                generationId,
                                Instant.parse("2026-09-18T00:00:00Z"),
                                List.of(items)))
                .when(candidateReader)
                .read(USER);
    }

    private RecommendationCandidate candidate(long movieId, String score, String type) {
        return new RecommendationCandidate(
                movieId,
                new BigDecimal(score),
                type,
                null,
                new tools.jackson.databind.ObjectMapper().createObjectNode());
    }

    private void user(long id, String nickname) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                id,
                nickname + "@example.test",
                nickname);
    }

    private void movie(long id) {
        jdbc.update(
                "INSERT INTO movies(id,tmdb_id,title) VALUES (?,?,?)",
                id,
                Math.toIntExact(id + 10000),
                "영화 " + id);
    }

    private void recommendationSet(long id, long userId, String generationId) {
        jdbc.update(
                "INSERT INTO recommendation_sets"
                        + "(id,user_id,input_version,model_version,generation_id,generated_at)"
                        + " VALUES (?,?,'input-v1','model-v1',?,?)",
                id,
                userId,
                generationId,
                OffsetDateTime.parse("2026-09-17T00:00:00Z"));
    }

    private void item(long id, long movieId, String type, int position) {
        jdbc.update(
                "INSERT INTO recommendation_items"
                        + "(id,set_id,movie_id,type,position,reason,exposed_at)"
                        + " VALUES (?,?,?,?,?,"
                        + "'{\"summary\":\"추천 이유\",\"factors\":[]}'::jsonb,NULL)",
                id,
                SET,
                movieId,
                type,
                position);
    }

    private List<Long> exposedIds() {
        return jdbc.queryForList(
                "SELECT id FROM recommendation_items WHERE exposed_at IS NOT NULL ORDER BY id",
                Long.class);
    }
}
