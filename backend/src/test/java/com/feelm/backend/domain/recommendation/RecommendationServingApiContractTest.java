package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.task.scheduling.enabled=false"
        })
@AutoConfigureMockMvc
@DisplayName("API-045·046 추천 버퍼 노출")
class RecommendationServingApiContractTest extends SharedPostgresContractTest {

    private static final long USER = 10;
    private static final long OTHER_USER = 20;
    private static final long SET = 1000;
    private static final long FLAVOR = 1;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;

    private ExecutorService executor;

    @BeforeEach
    void fixtures() {
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

    @AfterEach
    void stopExecutor() throws InterruptedException {
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

        replenish(USER, SET, 1001)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replacement.recommendationItemId").value(1003));

        getRecommendations(USER)
                .andExpect(jsonPath("$.items[0].recommendationItemId").value(1002))
                .andExpect(jsonPath("$.items[1].recommendationItemId").value(1003))
                .andExpect(jsonPath("$.items[2].recommendationItemId").value(1005));
    }

    @Test
    @DisplayName("평가로 빠진 추천 카드도 원본 유형의 다음 후보로 보충한다")
    void replenishesCardRemovedByRating() throws Exception {
        getRecommendations(USER).andExpect(status().isOk());
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (?,101,5)", USER);

        replenish(USER, SET, 1001)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replacement.recommendationItemId").value(1003))
                .andExpect(jsonPath("$.replacement.type").value("TASTE"));

        getRecommendations(USER)
                .andExpect(jsonPath("$.items[0].recommendationItemId").value(1002))
                .andExpect(jsonPath("$.items[1].recommendationItemId").value(1003))
                .andExpect(jsonPath("$.items[2].recommendationItemId").value(1005));
    }

    @Test
    @DisplayName("관심없음이나 평가로 제거되지 않은 카드는 보충 요청할 수 없다")
    void rejectsSourceThatIsStillVisible() throws Exception {
        getRecommendations(USER).andExpect(status().isOk());

        replenish(USER, SET, 1001)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(exposedIds()).containsExactly(1001L, 1002L, 1005L);
    }

    @Test
    @DisplayName("T6 DISCOVERY 카드가 빠지고 후보가 없으면 TASTE 한 편으로 보충한다")
    void replenishesDiscoveryWithTasteFallback() throws Exception {
        getRecommendations(USER).andExpect(status().isOk());
        jdbc.update("DELETE FROM recommendation_items WHERE id=1006");
        dismissal(105, 1005);

        replenish(USER, SET, 1005)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replacement.recommendationItemId").value(1003))
                .andExpect(jsonPath("$.replacement.type").value("TASTE"));

        getRecommendations(USER)
                .andExpect(jsonPath("$.items[0].recommendationItemId").value(1001))
                .andExpect(jsonPath("$.items[1].recommendationItemId").value(1002))
                .andExpect(jsonPath("$.items[2].recommendationItemId").value(1003));
    }

    @Test
    @DisplayName("같은 TASTE 원본을 재전송해도 비어 있는 DISCOVERY 자리를 잠식하지 않는다")
    void repeatedTasteSourceDoesNotConsumeDiscoverySlot() throws Exception {
        getRecommendations(USER).andExpect(status().isOk());
        dismissal(101, 1001);
        dismissal(105, 1005);

        replenish(USER, SET, 1001)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replacement.recommendationItemId").value(1003));
        replenish(USER, SET, 1001)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replacement").value(nullValue()));
        replenish(USER, SET, 1005)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replacement.recommendationItemId").value(1006));

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
    @DisplayName("T7 다른 사용자의 추천 세트는 조회할 수 없다")
    void rejectsForeignSet() throws Exception {
        replenish(OTHER_USER, SET, 1001)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(exposedIds()).isEmpty();
    }

    @Test
    @DisplayName("T8 최신 세트가 아닌 추천 세트는 추가 노출할 수 없다")
    void rejectsStaleSet() throws Exception {
        recommendationSet(2000, USER, OffsetDateTime.parse("2026-09-17T00:01:00Z"));

        dismissal(101, 1001);

        replenish(USER, SET, 1001)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECOMMENDATION_SET_STALE"));

        assertThat(exposedIds()).isEmpty();
    }

    @Test
    @DisplayName("T9 같은 빈 자리에 대한 동시 보충 요청은 후보를 중복 소비하지 않는다")
    void concurrentReplenishRequestsDoNotDuplicateItems() throws Exception {
        getRecommendations(USER).andExpect(status().isOk());
        dismissal(101, 1001);
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        String token = bearer(USER);
        Future<String> first = executor.submit(() -> concurrentReplenish(token, ready, start));
        Future<String> second = executor.submit(() -> concurrentReplenish(token, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .anySatisfy(body -> assertThat(body).contains("\"replacement\":{"))
                .anySatisfy(body -> assertThat(body).contains("\"replacement\":null"));
        assertThat(exposedIds()).containsExactly(1001L, 1002L, 1003L, 1005L);
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
                        + " reason='{\"summary\":\"저장된 이유\",\"factors\":[]}'::jsonb"
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
                .andExpect(jsonPath("$.items[0].reason.summary").value("저장된 이유"));
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

        replenish(USER, SET, 1001)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replacement").value(nullValue()));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_sets", Integer.class))
                .isEqualTo(1);
        getRecommendations(USER).andExpect(jsonPath("$.items.length()").value(2));
    }

    private org.springframework.test.web.servlet.ResultActions getRecommendations(long userId)
            throws Exception {
        return mvc.perform(get("/api/v1/recommendations").header("Authorization", bearer(userId)));
    }

    private org.springframework.test.web.servlet.ResultActions replenish(
            long userId, long setId, long recommendationItemId) throws Exception {
        return mvc.perform(
                post("/api/v1/recommendations/replenish")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"recommendationSetId\":"
                                        + setId
                                        + ",\"recommendationItemId\":"
                                        + recommendationItemId
                                        + "}"));
    }

    private String concurrentReplenish(String token, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return mvc.perform(
                        post("/api/v1/recommendations/replenish")
                                .header("Authorization", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"recommendationSetId\":"
                                                + SET
                                                + ",\"recommendationItemId\":1001}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
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

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
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

    private List<Long> exposedIds() {
        return jdbc.queryForList(
                "SELECT id FROM recommendation_items WHERE exposed_at IS NOT NULL ORDER BY id",
                Long.class);
    }
}
