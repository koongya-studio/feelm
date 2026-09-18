package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.recommendation.model.RecommendationCandidate;
import com.feelm.backend.domain.recommendation.model.RecommendationCandidateBatch;
import com.feelm.backend.domain.recommendation.repository.RecommendationCandidateReader;
import com.feelm.backend.domain.recommendation.service.RecommendationReplenisher;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;

@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.task.scheduling.enabled=false"
        })
@AutoConfigureMockMvc
@DisplayName("API-045 추천 버퍼 노출과 자동 보충")
class RecommendationServingApiContractTest extends SharedPostgresContractTest {

    private static final long USER = 10;
    private static final long OTHER_USER = 20;
    private static final long SET = 1000;
    private static final long FLAVOR = 1;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TokenProvider tokens;
    @Autowired RecommendationReplenisher replenisher;
    @MockitoBean RecommendationCandidateReader candidateReader;

    private ExecutorService executor;

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
        flavor(FLAVOR, "FLAVOR_B");
        user(USER, "user");
        user(OTHER_USER, "other");
        recommendationSet(SET, USER, OffsetDateTime.parse("2026-09-17T00:00:00Z"));
        for (long movieId = 101; movieId <= 106; movieId++) {
            movie(movieId);
        }
        item(1001, 101, "TASTE", 1, false);
        item(1002, 102, "TASTE", 2, false);
        item(1003, 103, "TASTE", 3, false);
        item(1004, 104, "TASTE", 4, false);
        item(1005, 105, "DISCOVERY", 1, false);
        item(1006, 106, "DISCOVERY", 2, false);
    }

    @Test
    @DisplayName("S15P21E106-613 최신 Redis generation을 조회 시 새 세트로 생성해 제공한다")
    void createsAndServesLatestCandidateGenerationOnRead() throws Exception {
        candidates(
                "generation-new",
                candidate(104, "5.0", "TASTE", null),
                candidate(105, "4.0", "TASTE", null),
                candidate(106, "3.0", "DISCOVERY", "FLAVOR_B"));

        String response =
                getRecommendations(USER)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        Long newSetId =
                jdbc.queryForObject(
                        "SELECT id FROM recommendation_sets WHERE generation_id='generation-new'",
                        Long.class);
        assertThat(response).contains("\"recommendationSetId\":" + newSetId);
    }

    @Test
    @DisplayName("S15P21E106-613 같은 Redis generation 반복 조회는 같은 세트를 재사용한다")
    void reusesSetForSameCandidateGenerationOnRepeatedRead() throws Exception {
        candidates(
                "generation-same",
                candidate(101, "5.0", "TASTE", null),
                candidate(102, "4.0", "TASTE", null),
                candidate(105, "3.0", "DISCOVERY", "FLAVOR_B"));

        String first =
                getRecommendations(USER)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String second =
                getRecommendations(USER)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(first).isEqualTo(second);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM recommendation_sets"
                                        + " WHERE generation_id='generation-same'",
                                Integer.class))
                .isEqualTo(1);
    }

    @AfterEach
    void stopExecutor() throws InterruptedException {
        jdbc.execute(
                "DROP TRIGGER IF EXISTS test_fail_recommendation_exposure"
                        + " ON recommendation_items");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_recommendation_exposure()");
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("T1 기본 추천은 TASTE 2편과 DISCOVERY 1편만 함께 노출한다")
    void exposesExactInitialComposition() throws Exception {
        getRecommendations(USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationSetId").value(SET))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].recommendationItemId").value(1001))
                .andExpect(jsonPath("$.items[1].recommendationItemId").value(1002))
                .andExpect(jsonPath("$.items[2].recommendationItemId").value(1005));

        assertThat(exposedIds()).containsExactly(1001L, 1002L, 1005L);
    }

    @Test
    @DisplayName("T2 기본 추천을 반복 조회해도 다음 버퍼를 소비하지 않는다")
    void returnsSameInitialItemsIdempotently() throws Exception {
        getRecommendations(USER).andExpect(status().isOk());
        getRecommendations(USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recommendationItemId").value(1001))
                .andExpect(jsonPath("$.items[2].recommendationItemId").value(1005));

        assertThat(exposedIds()).containsExactly(1001L, 1002L, 1005L);
    }

    @Test
    @DisplayName("보충 컴포넌트는 활성 트랜잭션 밖에서 호출할 수 없다")
    void replenisherRequiresExistingTransaction() {
        assertThatThrownBy(() -> replenisher.replenishVacancies(USER, SET, List.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("T3 현재 추천 세트가 없으면 503을 반환하고 새 세트를 만들지 않는다")
    void returnsUnavailableWhenNoSetExists() throws Exception {
        jdbc.update("DELETE FROM recommendation_sets");

        getRecommendations(USER)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECOMMENDATION_UNAVAILABLE"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_sets", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("T4 DISCOVERY 후보가 없으면 TASTE 3편을 노출한다")
    void fallsBackToThreeTasteItems() throws Exception {
        jdbc.update("DELETE FROM recommendation_items WHERE type='DISCOVERY'");

        getRecommendations(USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].recommendationItemId").value(1001))
                .andExpect(jsonPath("$.items[1].recommendationItemId").value(1002))
                .andExpect(jsonPath("$.items[2].recommendationItemId").value(1003));

        assertThat(exposedIds()).containsExactly(1001L, 1002L, 1003L);
    }

    @Test
    @DisplayName("저장 이후 평가한 후보를 제외하고 다음 유효 후보를 노출한다")
    void excludesMovieRatedAfterBufferWasStored() throws Exception {
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (?,101,5)", USER);

        getRecommendations(USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recommendationItemId").value(1002))
                .andExpect(jsonPath("$.items[1].recommendationItemId").value(1003))
                .andExpect(jsonPath("$.items[2].recommendationItemId").value(1005));

        assertThat(exposedIds()).containsExactly(1002L, 1003L, 1005L);
    }

    @Test
    @DisplayName("T5 TASTE 카드가 빠지면 기존 카드 뒤에 다음 TASTE 한 편만 보충한다")
    void replenishesTasteAndPreservesVisibleOrder() throws Exception {
        getRecommendations(USER).andExpect(status().isOk());
        dismissal(101, 1001);

        getRecommendations(USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recommendationItemId").value(1002))
                .andExpect(jsonPath("$.items[1].recommendationItemId").value(1003))
                .andExpect(jsonPath("$.items[2].recommendationItemId").value(1005));
    }

    @Test
    @DisplayName("평가로 빠진 추천 카드도 원본 유형의 다음 후보로 보충한다")
    void replenishesCardRemovedByRating() throws Exception {
        getRecommendations(USER).andExpect(status().isOk());
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (?,101,5)", USER);

        getRecommendations(USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recommendationItemId").value(1002))
                .andExpect(jsonPath("$.items[1].recommendationItemId").value(1003))
                .andExpect(jsonPath("$.items[2].recommendationItemId").value(1005));
    }

    @Test
    @DisplayName("유효 카드가 이미 세 편이면 다음 후보를 소비하지 않는다")
    void doesNotConsumeCandidateWhenSlotsAreFull() throws Exception {
        getRecommendations(USER).andExpect(status().isOk());
        getRecommendations(USER).andExpect(status().isOk());

        assertThat(exposedIds()).containsExactly(1001L, 1002L, 1005L);
    }

    @Test
    @DisplayName("T6 DISCOVERY 카드가 빠지고 후보가 없으면 TASTE 한 편으로 보충한다")
    void replenishesDiscoveryWithTasteFallback() throws Exception {
        getRecommendations(USER).andExpect(status().isOk());
        jdbc.update("DELETE FROM recommendation_items WHERE id=1006");
        dismissal(105, 1005);

        getRecommendations(USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recommendationItemId").value(1001))
                .andExpect(jsonPath("$.items[1].recommendationItemId").value(1002))
                .andExpect(jsonPath("$.items[2].recommendationItemId").value(1003));
    }

    @Test
    @DisplayName("조회 사이 두 카드가 빠지면 유형별 빈자리만 한 번에 보충한다")
    void fillsMultipleVacanciesOnRead() throws Exception {
        getRecommendations(USER).andExpect(status().isOk());
        dismissal(101, 1001);
        dismissal(105, 1005);

        getRecommendations(USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recommendationItemId").value(1002))
                .andExpect(jsonPath("$.items[1].recommendationItemId").value(1003))
                .andExpect(jsonPath("$.items[2].recommendationItemId").value(1006));

        assertThat(exposedIds()).containsExactly(1001L, 1002L, 1003L, 1005L, 1006L);
    }

    @Test
    @DisplayName("서로 다른 사용자가 같은 영화를 동시에 추천받아도 교착하지 않는다")
    void concurrentInitialExposureForDifferentUsersDoesNotDeadlock() throws Exception {
        long otherSet = 2000;
        recommendationSet(otherSet, OTHER_USER, OffsetDateTime.parse("2026-09-17T00:00:00Z"));
        jdbc.update(
                "INSERT INTO recommendation_items"
                        + "(id,set_id,movie_id,type,position,reason) VALUES"
                        + "(2001,2000,101,'TASTE',1,'{}'),"
                        + "(2002,2000,102,'TASTE',2,'{}'),"
                        + "(2003,2000,105,'DISCOVERY',1,'{}')");
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> first = executor.submit(() -> concurrentGet(USER, ready, start));
        Future<String> second = executor.submit(() -> concurrentGet(OTHER_USER, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS)).contains("\"recommendationSetId\":1000");
        assertThat(second.get(10, TimeUnit.SECONDS)).contains("\"recommendationSetId\":2000");
    }

    @Test
    @DisplayName("T7 공개 카드 보충 API는 제거한다")
    void removesPublicReplenishEndpoint() throws Exception {
        mvc.perform(post("/api/v1/recommendations/replenish").header("Authorization", bearer(USER)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("T8 같은 사용자의 동시 조회는 빈자리를 중복 보충하지 않는다")
    void concurrentReadsDoNotDuplicateItems() throws Exception {
        getRecommendations(USER).andExpect(status().isOk());
        dismissal(101, 1001);
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> first = executor.submit(() -> concurrentGet(USER, ready, start));
        Future<String> second = executor.submit(() -> concurrentGet(USER, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS)).contains("\"recommendationItemId\":1003");
        assertThat(second.get(10, TimeUnit.SECONDS)).contains("\"recommendationItemId\":1003");
        assertThat(exposedIds()).containsExactly(1001L, 1002L, 1003L, 1005L);
    }

    @Test
    @DisplayName("같은 사용자의 추천 조회는 사용자 행 잠금이 풀린 뒤 진행한다")
    void waitsForUserLockBeforeServingRecommendations() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection();
                PreparedStatement lock =
                        blocker.prepareStatement(
                                "SELECT id FROM users WHERE id=? FOR NO KEY UPDATE")) {
            blocker.setAutoCommit(false);
            lock.setLong(1, USER);
            lock.executeQuery();

            Future<String> response = executor.submit(() -> concurrentGetWithoutBarrier(USER));
            Thread.sleep(300);
            assertThat(response).isNotDone();

            blocker.commit();
            assertThat(response.get(10, TimeUnit.SECONDS)).contains("\"recommendationSetId\":1000");
        }
    }

    @Test
    @DisplayName("T10 추천 당시 이유와 영화·장르·OTT 표시 정보를 반환한다")
    void mapsStoredReasonAndMoviePresentationData() throws Exception {
        jdbc.update(
                "UPDATE movies SET poster_url='https://image.test/101.jpg',"
                        + " release_date=DATE '1999-10-15',average_rating=4.30 WHERE id=101");
        jdbc.update(
                "INSERT INTO genres(id,code,name,tmdb_genre_id)" + " VALUES (1,'DRAMA','드라마',18)");
        jdbc.update("INSERT INTO movie_genres(movie_id,genre_id) VALUES (101,1)");
        jdbc.update(
                "INSERT INTO otts(id,code,name,tmdb_provider_id)"
                        + " VALUES (1,'NETFLIX','넷플릭스',8)");
        jdbc.update("INSERT INTO movie_ott_providers(movie_id,ott_id) VALUES (101,1)");
        jdbc.update(
                "UPDATE recommendation_items SET flavor_code='FLAVOR_SNAPSHOT',"
                        + " reason='{\"summary\":\"저장된 이유\",\"factors\":[],"
                        + "\"reasons\":[{\"code\":\"SHARED_GENRE\"}]}'::jsonb"
                        + " WHERE id=1001");

        getRecommendations(USER)
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items[0].movie.posterUrl").value("https://image.test/101.jpg"))
                .andExpect(jsonPath("$.items[0].movie.releaseYear").value(1999))
                .andExpect(jsonPath("$.items[0].movie.genres[0]").value("드라마"))
                .andExpect(jsonPath("$.items[0].movie.averageRating").value(4.3))
                .andExpect(jsonPath("$.items[0].movie.ottProviders[0]").value("NETFLIX"))
                .andExpect(jsonPath("$.items[0].flavorCode").value("FLAVOR_SNAPSHOT"))
                .andExpect(jsonPath("$.items[0].reason.summary").value("저장된 이유"))
                .andExpect(jsonPath("$.items[0].reason.reasons").doesNotExist());
    }

    @Test
    @DisplayName("추천 카드·영화 상세·이력은 저장된 같은 이유를 반환하고 원본 근거를 감춘다")
    void cardDetailAndHistorySharePublicReasonSnapshot() throws Exception {
        jdbc.update(
                "UPDATE recommendation_items SET reason='{\"reasons\":[{\"code\":\"SHARED_GENRE\"}],"
                        + "\"summary\":\"검증된 이유\",\"factors\":[{\"type\":\"GENRE\",\"label\":\"드라마\"}]}'::jsonb"
                        + " WHERE id=1001");

        getRecommendations(USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reason.summary").value("검증된 이유"))
                .andExpect(jsonPath("$.items[0].reason.factors[0].label").value("드라마"))
                .andExpect(jsonPath("$.items[0].reason.reasons").doesNotExist());
        mvc.perform(get("/api/v1/recommendations/history").header("Authorization", bearer(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].items[0].reason.summary").value("검증된 이유"))
                .andExpect(jsonPath("$.items[0].items[0].reason.factors[0].label").value("드라마"))
                .andExpect(jsonPath("$.items[0].items[0].reason.reasons").doesNotExist());
        mvc.perform(
                        get("/api/v1/movies/101")
                                .param("recommendationItemId", "1001")
                                .header("Authorization", bearer(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendation.reason.summary").value("검증된 이유"))
                .andExpect(jsonPath("$.recommendation.reason.factors[0].label").value("드라마"))
                .andExpect(jsonPath("$.recommendation.reason.reasons").doesNotExist());
    }

    @Test
    @DisplayName("추천 당시 맛이 없으면 영화 대표 맛을 반환한다")
    void fallsBackToMovieFlavor() throws Exception {
        jdbc.update("UPDATE movies SET representative_flavor_id=? WHERE id=101", FLAVOR);

        getRecommendations(USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].flavorCode").value("FLAVOR_B"));
    }

    @Test
    @DisplayName("T11 인증하지 않으면 추천 버퍼를 노출하지 않는다")
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/recommendations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(exposedIds()).isEmpty();
    }

    @Test
    @DisplayName("T12 보충 후보가 없으면 기존 카드를 유지하고 새 세트를 만들지 않는다")
    void noReplacementKeepsExistingCards() throws Exception {
        getRecommendations(USER).andExpect(status().isOk());
        dismissal(101, 1001);
        jdbc.update("DELETE FROM recommendation_items WHERE id IN (1003,1004)");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_sets", Integer.class))
                .isEqualTo(1);
        getRecommendations(USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    @DisplayName("보충 노출 실패는 기존 관심없음 저장을 유지하고 이번 조회 쓰기만 롤백한다")
    void exposureFailureDoesNotRollBackDismissal() throws Exception {
        getRecommendations(USER).andExpect(status().isOk());
        mvc.perform(
                        post("/api/v1/movies/101/dismiss")
                                .header("Authorization", bearer(USER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"recommendationItemId\":1001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movieId").value(101));
        failingExposureTrigger();

        getRecommendations(USER)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM user_movie_dismissals"
                                        + " WHERE user_id=? AND movie_id=101",
                                Integer.class,
                                USER))
                .isEqualTo(1);
        assertThat(exposedIds()).containsExactly(1001L, 1002L, 1005L);
    }

    private org.springframework.test.web.servlet.ResultActions getRecommendations(long userId)
            throws Exception {
        return mvc.perform(get("/api/v1/recommendations").header("Authorization", bearer(userId)));
    }

    private String concurrentGet(long userId, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return getRecommendations(userId)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String concurrentGetWithoutBarrier(long userId) throws Exception {
        return getRecommendations(userId)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    private void candidates(String generationId, RecommendationCandidate... items) {
        doReturn(
                        new RecommendationCandidateBatch(
                                "als-serving-example",
                                10L,
                                generationId,
                                Instant.parse("2026-09-18T00:00:00Z"),
                                List.of(items)))
                .when(candidateReader)
                .read(USER);
    }

    private RecommendationCandidate candidate(
            long movieId, String score, String type, String flavorCode) {
        return new RecommendationCandidate(
                movieId,
                new BigDecimal(score),
                type,
                flavorCode,
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

    private void flavor(long id, String code) {
        jdbc.update(
                "INSERT INTO popcorn_flavors"
                        + "(id,code,display_name,color,sort_order,cluster_id,representative_keywords)"
                        + " VALUES (?,?,'대표 맛','#000000',?,?,CAST('[]' AS jsonb))",
                id,
                code,
                Math.toIntExact(id),
                Math.toIntExact(id));
    }

    private void movie(long id) {
        jdbc.update(
                "INSERT INTO movies(id,tmdb_id,title) VALUES (?,?,?)",
                id,
                Math.toIntExact(id + 10000),
                "영화 " + id);
    }

    private void recommendationSet(long id, long userId, OffsetDateTime generatedAt) {
        jdbc.update(
                "INSERT INTO recommendation_sets"
                        + "(id,user_id,input_version,model_version,generated_at)"
                        + " VALUES (?,?,'input-v1','model-v1',?)",
                id,
                userId,
                generatedAt);
    }

    private void item(long id, long movieId, String type, int position, boolean exposed) {
        jdbc.update(
                "INSERT INTO recommendation_items"
                        + "(id,set_id,movie_id,type,position,reason,exposed_at)"
                        + " VALUES (?,?,?,?,?,'{\"summary\":\"추천 이유\",\"factors\":[]}'::jsonb,?)",
                id,
                SET,
                movieId,
                type,
                position,
                exposed ? OffsetDateTime.now() : null);
    }

    private void dismissal(long movieId, long recommendationItemId) {
        jdbc.update(
                "INSERT INTO user_movie_dismissals(user_id,movie_id,recommendation_item_id)"
                        + " VALUES (?,?,?)",
                USER,
                movieId,
                recommendationItemId);
    }

    private void failingExposureTrigger() {
        jdbc.execute(
                """
                CREATE FUNCTION test_fail_recommendation_exposure() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'forced exposure failure'; END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute(
                """
                CREATE TRIGGER test_fail_recommendation_exposure
                BEFORE UPDATE OF exposed_at ON recommendation_items
                FOR EACH ROW WHEN (OLD.exposed_at IS NULL AND NEW.exposed_at IS NOT NULL)
                EXECUTE FUNCTION test_fail_recommendation_exposure()
                """);
    }

    private List<Long> exposedIds() {
        return jdbc.queryForList(
                "SELECT id FROM recommendation_items WHERE exposed_at IS NOT NULL ORDER BY id",
                Long.class);
    }
}
