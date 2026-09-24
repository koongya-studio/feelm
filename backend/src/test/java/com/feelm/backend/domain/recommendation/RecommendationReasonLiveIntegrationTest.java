package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.recommendation.model.RecommendationBufferResult;
import com.feelm.backend.domain.recommendation.service.RecommendationBufferService;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.infrastructure.gemini.GeminiRecommendationReasonGenerator;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 수동 실행: 실제 GMS 키가 있을 때 Redis v6 → DB → GMS → 세 응답을 검증한다. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@Tag("live")
@EnabledIfEnvironmentVariable(named = "GMS_KEY", matches = ".+")
class RecommendationReasonLiveIntegrationTest extends SharedPostgresContractTest {

    private static final long USER = 49801;
    private static final String HASH = "sha256:" + "b".repeat(64);
    private static final String CANDIDATES_KEY = "feelm:user:" + USER + ":candidates";
    private static final String STATE_KEY = "feelm:user:" + USER + ":recommendation-state";
    private static final String CURRENT_MODEL_KEY = "feelm:model:current";
    private String evidenceKey;

    @DynamicPropertySource
    static void gmsKey(DynamicPropertyRegistry registry) {
        registry.add("feelm.recommendation.reason.gemini.api-key", () -> System.getenv("GMS_KEY"));
        registry.add("feelm.recommendation.reason.gbt-ai.enabled", () -> "true");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired RecommendationBufferService buffer;
    @Autowired MockMvc mvc;
    @Autowired TokenProvider tokens;
    @Autowired ObjectMapper mapper;
    @Autowired Environment environment;
    @MockitoSpyBean GeminiRecommendationReasonGenerator gemini;

    @BeforeEach
    void seed() {
        jdbc.execute(
                "TRUNCATE TABLE user_movie_dismissals, ratings, watch_records,"
                        + " movie_ott_providers, recommendation_items, recommendation_sets,"
                        + " movie_genres, movies, users, genres, otts, popcorn_flavors"
                        + " RESTART IDENTITY CASCADE");
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,'live-498@example.test','live498',DATE '2000-01-01','OTHER','ACTIVE')",
                USER);
        for (long movieId : new long[] {90, 101, 102, 201}) {
            jdbc.update(
                    "INSERT INTO movies(id,tmdb_id,title) VALUES (?,?,?)",
                    movieId,
                    Math.toIntExact(90000 + movieId),
                    movieId == 90 ? "근거 영화" : "후보 영화 " + movieId);
        }
        jdbc.update(
                "INSERT INTO genres(id,code,name,tmdb_genre_id)"
                        + " VALUES (1,'THRILLER','스릴러',53)");
        jdbc.update("INSERT INTO movie_genres(movie_id,genre_id) VALUES (90,1),(101,1)");
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (?,90,5)", USER);
        String generation = UUID.randomUUID().toString();
        evidenceKey = "feelm:user:" + USER + ":gbt-evidence:" + generation;
        redis.opsForValue()
                .multiSet(
                        Map.of(
                                CURRENT_MODEL_KEY,
                                """
                                {"modelKind":"GBT","modelVersion":"gbt-live",
                                 "servingRevision":"serving-live"}
                                """,
                                STATE_KEY,
                                """
                                {"stateVersion":1,"userId":%d,"modelKind":"GBT",
                                 "modelVersion":"gbt-live","servingRevision":"serving-live",
                                 "generationId":"%s","inputSchemaVersion":"gbt-input-v1",
                                 "inputHash":"%s","ratingCount":1,"dismissCount":0,
                                 "supportedInputCount":1,"updatedAt":"2026-09-17T12:00:00Z"}
                                """
                                        .formatted(USER, generation, HASH),
                                CANDIDATES_KEY,
                                """
                        {"modelVersion":"gbt-live","servingRevision":"serving-live",
                         "candidateVersion":6,"generationId":"%s",
                         "inputSchemaVersion":"gbt-input-v1","inputHash":"%s",
                         "updatedAt":"2026-09-17T12:00:00Z",
                         "taste":[
                           {"movieId":101,"score":0.9,"reasons":[]},
                           {"movieId":102,"score":0.8,"reasons":[]}],
                         "discovery":[{"movieId":201,"score":0.7,"reasons":[]}]}
                        """
                                        .formatted(generation, HASH),
                                evidenceKey,
                                """
                                {"evidenceSchemaVersion":"gbt-evidence-v1",
                                 "explanationVersion":"gbt-feature-attribution-v1",
                                 "userId":%d,"generationId":"%s","modelVersion":"gbt-live",
                                 "servingRevision":"serving-live",
                                 "featureVersion":"v26-max70-kobis-flag-71-v1",
                                 "rankingVersion":"gbt-personal-raw-score-v1",
                                 "inputVersion":"%s","candidateVersion":6,"items":[
                                   {"movieId":101,"score":0.9,"coverage":0.7,"evidence":[{
                                     "evidenceId":"sha256:%s","group":"GENRE_PATTERN",
                                     "direction":"POSITIVE","contribution":0.4,"coverage":0.7}]},
                                   {"movieId":102,"score":0.8,"coverage":0,"evidence":[]}]}
                                """
                                        .formatted(USER, generation, HASH, "a".repeat(64))));
    }

    @AfterEach
    void clearRedis() {
        redis.delete(Set.of(CANDIDATES_KEY, STATE_KEY, CURRENT_MODEL_KEY, evidenceKey));
    }

    @Test
    void readsV6PersistsLiveGmsReasonAndReturnsSamePublicSnapshot() throws Exception {
        String configuredKey =
                environment.getProperty("feelm.recommendation.reason.gemini.api-key");
        assertThat(configuredKey != null && !configuredKey.isBlank()).isTrue();
        doAnswer(
                        invocation -> {
                            Map<?, ?> reasons = (Map<?, ?>) invocation.callRealMethod();
                            assertThat(reasons).hasSize(1);
                            return reasons;
                        })
                .when(gemini)
                .generateGbtReasons(anyList());

        RecommendationBufferResult result = buffer.createBuffer(USER);
        long setId = result.recommendationSetId();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT candidate_version FROM recommendation_sets WHERE id=?",
                                Long.class,
                                setId))
                .isEqualTo(6L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM recommendation_items WHERE set_id=? AND type='TASTE'",
                                Integer.class,
                                setId))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM recommendation_items WHERE set_id=? AND type='DISCOVERY'",
                                Integer.class,
                                setId))
                .isEqualTo(1);
        long itemId =
                jdbc.queryForObject(
                        "SELECT id FROM recommendation_items WHERE set_id=? AND movie_id=101",
                        Long.class,
                        setId);
        JsonNode stored =
                mapper.readTree(
                        jdbc.queryForObject(
                                "SELECT cast(reason AS text) FROM recommendation_items WHERE id=?",
                                String.class,
                                itemId));
        assertThat(stored.path("summary").textValue()).containsAnyOf("장르 취향 패턴", "추천");
        assertThat(stored.path("usedEvidenceIds").path(0).textValue())
                .isEqualTo("sha256:" + "a".repeat(64));
        assertThat(stored.path("factors").isEmpty()).isTrue();
        JsonNode noEvidence =
                mapper.readTree(
                        jdbc.queryForObject(
                                "SELECT cast(reason AS text) FROM recommendation_items"
                                        + " WHERE set_id=? AND movie_id=102",
                                String.class,
                                setId));
        assertThat(noEvidence.path("summary").textValue()).isEqualTo("취향 데이터를 바탕으로 영화를 골라봤어요.");
        assertThat(noEvidence.path("factors").isEmpty()).isTrue();

        String bearer = "Bearer " + tokens.createAccessToken(USER);
        JsonNode card =
                response(
                        mvc.perform(get("/api/v1/recommendations").header("Authorization", bearer))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(StandardCharsets.UTF_8));
        JsonNode detail =
                response(
                        mvc.perform(
                                        get("/api/v1/movies/101")
                                                .param(
                                                        "recommendationItemId",
                                                        Long.toString(itemId))
                                                .header("Authorization", bearer))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(StandardCharsets.UTF_8));
        JsonNode history =
                response(
                        mvc.perform(
                                        get("/api/v1/recommendations/history")
                                                .header("Authorization", bearer))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(StandardCharsets.UTF_8));
        JsonNode cardReason = card.path("items").path(0).path("reason");
        assertThat(card.path("items").size()).isEqualTo(3);
        JsonNode detailReason = detail.path("recommendation").path("reason");
        JsonNode historyReason = history.path("items").path(0).path("items").path(0).path("reason");
        assertThat(cardReason).isEqualTo(detailReason).isEqualTo(historyReason);
        assertThat(cardReason.path("summary").textValue()).containsAnyOf("장르 취향 패턴", "추천");
        assertThat(cardReason.has("reasons")).isFalse();
        assertThat(cardReason.has("usedEvidenceIds")).isFalse();
        assertThat(cardReason.has("evidence")).isFalse();
        verify(gemini, times(1)).generateGbtReasons(anyList());
    }

    private JsonNode response(String body) {
        return mapper.readTree(body);
    }
}
