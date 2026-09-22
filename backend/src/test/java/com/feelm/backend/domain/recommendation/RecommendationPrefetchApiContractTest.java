package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * API-045 추천 버퍼 프리페치와 서빙 회차 선택(S15P21E106-708).
 *
 * <p>프리페치는 응답을 만든 뒤 별도 실행기에 제출한다. 실행기를 동기 실행으로 바꾸면 "응답이 프리페치를 기다리지 않는다"는 완료 조건이 검증에서 빠지므로, 실제 실행기가
 * 비는 것을 기다려 확인한다. 제출은 응답을 돌려주기 전에 끝나므로 요청이 반환된 뒤 폴링해도 놓치지 않는다.
 */
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.task.scheduling.enabled=false",
            "feelm.recommendation.buffer.prefetch-threshold=5"
        })
@AutoConfigureMockMvc
@DisplayName("API-045 추천 버퍼 프리페치와 서빙 회차 선택")
class RecommendationPrefetchApiContractTest extends SharedPostgresContractTest {

    private static final long USER = 40;
    private static final long SET = 4000;
    private static final long FLAVOR = 1;
    private static final String GENERATION = "generation-prefetch";
    private static final String OTHER_GENERATION = "generation-prefetch-next";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;

    @Autowired
    @Qualifier("recommendationPrefetchExecutor")
    ThreadPoolTaskExecutor prefetchExecutor;

    @MockitoBean RecommendationCandidateReader candidateReader;

    private ExecutorService executor;

    /** 이 테스트가 제출하기 전까지 실행기가 끝낸 작업 수. 컨텍스트를 공유하므로 0에서 시작하지 않는다. */
    private long completedPrefetchTasks;

    @BeforeEach
    void fixtures() {
        completedPrefetchTasks = prefetchExecutor.getThreadPoolExecutor().getCompletedTaskCount();
        reset(candidateReader);
        when(candidateReader.read(anyLong()))
                .thenThrow(new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE));
        jdbc.execute(
                "TRUNCATE TABLE user_movie_dismissals, ratings, watch_records,"
                        + " movie_ott_providers, recommendation_items, recommendation_sets,"
                        + " movies, users, genres, otts, popcorn_flavors"
                        + " RESTART IDENTITY CASCADE");
        flavor(FLAVOR, "FLAVOR_C");
        user(USER, "prefetch");
        for (long movieId = 301; movieId <= 345; movieId++) {
            movie(movieId);
        }
        recommendationSet(SET, GENERATION, 0);
        // 노출 중인 3편(TASTE 2 + DISCOVERY 1). 목표 구성을 채우므로 조회가 보충하지 않는다.
        item(4001, SET, 301, "TASTE", 1, true);
        item(4002, SET, 302, "TASTE", 2, true);
        item(4003, SET, 320, "DISCOVERY", 1, true);
        jdbc.execute("SELECT setval(pg_get_serial_sequence('recommendation_sets','id'),4000,true)");
        jdbc.execute(
                "SELECT setval(pg_get_serial_sequence('recommendation_items','id'),4100,true)");
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
    @DisplayName("T1 미노출 유효 후보가 임계값 이하이면 다음 회차를 미리 만들고 응답은 현재 회차를 유지한다")
    void prefetchesNextBatchWhenUnexposedValidCandidatesAtThreshold() throws Exception {
        unexposedValid(5);
        nextBatchCandidates();
        long prefetchedSetId = nextSetId();

        getRecommendations()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationSetId").value(SET))
                .andExpect(jsonPath("$.items.length()").value(3));

        awaitPrefetchIdle();
        assertThat(setCount()).isEqualTo(2);
        assertThat(batchNoOf(prefetchedSetId)).isEqualTo(1);
        assertThat(generationOf(prefetchedSetId)).isEqualTo(GENERATION);
        // 미리 만든 회차는 노출하지 않는다. exposed_at이 NULL이라 추천 이력(API-048)에도 들어가지 않는다.
        assertThat(itemCount(prefetchedSetId)).isEqualTo(10);
        assertThat(exposedItemCount(prefetchedSetId)).isZero();
    }

    @Test
    @DisplayName("T2 미노출 유효 후보가 임계값을 넘으면 다음 회차를 만들지 않는다")
    void doesNotPrefetchWhenUnexposedValidCandidatesAboveThreshold() throws Exception {
        unexposedValid(6);
        nextBatchCandidates();

        getRecommendations()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationSetId").value(SET));

        awaitNoPrefetch();
        assertThat(setCount()).isEqualTo(1);
        verify(candidateReader, never()).read(USER);
    }

    @Test
    @DisplayName("T3 미리 만든 회차가 있어도 현재 회차의 남은 카드를 먼저 서빙한다")
    void servesRemainingCardsOfLowerBatchBeforePrefetchedBatch() throws Exception {
        unexposedValid(5);
        nextBatchCandidates();
        long prefetchedSetId = nextSetId();
        getRecommendations().andExpect(status().isOk());
        awaitPrefetchIdle();

        getRecommendations()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationSetId").value(SET))
                .andExpect(jsonPath("$.items.length()").value(3));

        awaitPrefetchIdle();
        assertThat(exposedItemCount(prefetchedSetId)).isZero();
        // 다음 회차가 이미 있으므로 두 번째 조회의 프리페치는 회차를 늘리지 않는다.
        assertThat(setCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("T4 현재 회차가 소진되면 재생성 없이 미리 만든 회차로 전환한다")
    void switchesToPrefetchedBatchAfterCurrentBatchIsExhausted() throws Exception {
        unexposedValid(5);
        nextBatchCandidates();
        long prefetchedSetId = nextSetId();
        getRecommendations().andExpect(status().isOk());
        awaitPrefetchIdle();
        exhaustCurrentBatch();

        getRecommendations()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationSetId").value(prefetchedSetId))
                .andExpect(jsonPath("$.items.length()").value(3));

        awaitNoPrefetch();
        // 프리페치 한 번이 전부다. 소진 시점에 Redis를 다시 읽지 않는 것이 이 작업의 목적이다.
        verify(candidateReader, times(1)).read(USER);
        assertThat(setCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("T5 새 generation이 도착하면 이전 generation에 유효 카드가 남아 있어도 즉시 전환한다")
    void switchesToNewGenerationEvenWhenLowerBatchStillHasValidCards() throws Exception {
        unexposedValid(5);
        long newGenerationSetId = 4500;
        recommendationSet(newGenerationSetId, OTHER_GENERATION, 0);
        item(4501, newGenerationSetId, 341, "TASTE", 1, false);
        item(4502, newGenerationSetId, 342, "TASTE", 2, false);
        item(4503, newGenerationSetId, 343, "DISCOVERY", 1, false);

        getRecommendations()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationSetId").value(newGenerationSetId))
                .andExpect(jsonPath("$.items.length()").value(3));

        awaitPrefetchIdle();
        // 이전 generation의 노출 이력은 보존한다.
        assertThat(exposedItemCount(SET)).isEqualTo(3);
    }

    @Test
    @DisplayName("T6 프리페치가 실패해도 남은 유효 카드를 그대로 반환한다")
    void returnsRemainingCardsWhenPrefetchFails() throws Exception {
        unexposedValid(5);

        getRecommendations()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationSetId").value(SET))
                .andExpect(jsonPath("$.items.length()").value(3));

        awaitPrefetchIdle();
        verify(candidateReader, times(1)).read(USER);
        assertThat(setCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T7 Redis가 새 generation으로 바뀌었으면 프리페치는 회차를 만들지 않는다")
    void doesNotPrefetchWhenRedisGenerationDiffers() throws Exception {
        unexposedValid(5);
        candidates(
                OTHER_GENERATION,
                candidate(331, "5.0", "TASTE"),
                candidate(332, "4.0", "TASTE"),
                candidate(338, "3.0", "DISCOVERY"));

        getRecommendations()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationSetId").value(SET));

        awaitPrefetchIdle();
        // 새 generation의 첫 회차는 Candidate 알림 경로가 소유한다.
        verify(candidateReader, times(1)).read(USER);
        assertThat(setCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T8 동시 조회가 모두 프리페치를 판정해도 회차는 한 번만 늘어난다")
    void createsNextBatchOnlyOnceUnderConcurrentRequests() throws Exception {
        unexposedValid(5);
        // Redis 읽기를 배리어로 묶어 두 프리페치가 반드시 겹치게 한다. 묶지 않으면 먼저 제출된
        // 프리페치가 커밋을 마친 뒤 두 번째가 시작해, 잠금 밖 사전 확인에서 끝나 버린다. 그러면
        // 잠금 후 재확인을 지워도 이 테스트가 통과한다.
        nextBatchCandidatesAfter(new CyclicBarrier(2));
        long prefetchedSetId = nextSetId();

        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> first = executor.submit(() -> concurrentGet(ready, start));
        Future<Integer> second = executor.submit(() -> concurrentGet(ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(200);
        awaitPrefetchCompleted(completedPrefetchTasks + 2);
        // 두 요청이 모두 프리페치를 판정했음을 고정한다. 한쪽만 판정하면 경합 자체가 성립하지 않는다.
        verify(candidateReader, times(2)).read(USER);
        assertThat(setCount()).isEqualTo(2);
        assertThat(batchNoOf(prefetchedSetId)).isEqualTo(1);
        // 잠금 후 재확인을 지우면 두 트랜잭션이 같은 batch_no를 계산하고, 진 쪽의 INSERT가 고유
        // 인덱스에 걸려 롤백되면서 시퀀스를 한 칸 더 먹는다. 세트 수만 보면 그 차이가 드러나지 않는다.
        assertThat(nextSetId()).isEqualTo(prefetchedSetId + 1);
    }

    @Test
    @DisplayName("T9 앞선 회차가 노출을 시작한 뒤에는 되살아난 이전 회차로 돌아가지 않는다")
    void doesNotFallBackToEarlierBatchAfterLaterBatchStartedExposure() throws Exception {
        unexposedValid(5);
        nextBatchCandidates();
        long prefetchedSetId = nextSetId();
        getRecommendations().andExpect(status().isOk());
        awaitPrefetchIdle();
        exhaustCurrentBatch();
        // batch 1이 노출을 시작한다.
        getRecommendations()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationSetId").value(prefetchedSetId));
        awaitNoPrefetch();

        // 그 뒤 batch 0의 카드가 평가 철회로 다시 유효해진다.
        jdbc.update("DELETE FROM ratings WHERE user_id=? AND movie_id IN (303,304,305)", USER);

        // 진행 하한이 없으면 batch 0이 "미소진 최저 회차"가 되어 이미 보여준 영화를 다시 보여준다.
        getRecommendations()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationSetId").value(prefetchedSetId));
    }

    @Test
    @DisplayName("T10 프리페치를 켜도 응답은 Redis 읽기를 기다리지 않는다")
    void responseDoesNotWaitForPrefetchRedisRead() throws Exception {
        unexposedValid(5);
        // Redis 읽기를 막아 둔다. 응답이 이걸 기다린다면 아래 조회가 돌아오지 못한다.
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                            return candidateBatch(GENERATION, candidate(331, "9.0", "TASTE"));
                        })
                .when(candidateReader)
                .read(USER);

        getRecommendations()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationSetId").value(SET))
                .andExpect(jsonPath("$.items.length()").value(3));

        // 여기 도달한 것 자체가 응답 경로가 Redis를 기다리지 않았다는 증거다.
        release.countDown();
        awaitPrefetchIdle();
    }

    /** 현재 세트에 미노출 유효 후보를 {@code count}편 넣는다. TASTE와 DISCOVERY를 합해 센다. */
    private void unexposedValid(int count) {
        long itemId = 4010;
        long movieId = 303;
        for (int index = 0; index < count; index++) {
            String type = index == 0 ? "DISCOVERY" : "TASTE";
            item(itemId++, SET, movieId++, type, index + 10, false);
        }
    }

    /** 현재 세트의 유효 카드를 모두 없앤다. 노출·미노출을 가리지 않는다. */
    private void exhaustCurrentBatch() {
        jdbc.update(
                "INSERT INTO ratings(user_id,movie_id,score)"
                        + " SELECT ?,i.movie_id,4 FROM recommendation_items i WHERE i.set_id=?",
                USER,
                SET);
    }

    /**
     * 두 프리페치가 모두 후보를 읽은 뒤에야 쓰기 트랜잭션에 들어가도록 묶는다.
     *
     * <p>후보를 타입당 버퍼 크기(20)보다 많이 준다. 후보가 적으면 첫 회차가 전부 가져가 두 번째 회차가 어차피 비므로, 잠금 후 재확인을 지워도 회차가 늘지 않아
     * 이 테스트가 통과해 버린다.
     */
    private void nextBatchCandidatesAfter(CyclicBarrier barrier) {
        RecommendationCandidateBatch batch = candidateBatch(GENERATION, overflowingBatchItems());
        doAnswer(
                        invocation -> {
                            barrier.await(10, TimeUnit.SECONDS);
                            return batch;
                        })
                .when(candidateReader)
                .read(USER);
    }

    /** 1회차가 저장하지 않은 영화로 다음 회차 후보를 만든다. 7 TASTE + 3 DISCOVERY = 10편이다. */
    private void nextBatchCandidates() {
        candidates(GENERATION, nextBatchItems());
    }

    /** 한 회차(타입당 최대 20편)를 넘기는 TASTE 후보 25편이다. */
    private RecommendationCandidate[] overflowingBatchItems() {
        RecommendationCandidate[] items = new RecommendationCandidate[25];
        for (int index = 0; index < items.length; index++) {
            items[index] = candidate(321 + index, String.valueOf(25 - index) + ".0", "TASTE");
        }
        return items;
    }

    private RecommendationCandidate[] nextBatchItems() {
        return new RecommendationCandidate[] {
            candidate(331, "9.0", "TASTE"),
            candidate(332, "8.0", "TASTE"),
            candidate(333, "7.0", "TASTE"),
            candidate(334, "6.0", "TASTE"),
            candidate(335, "5.0", "TASTE"),
            candidate(336, "4.0", "TASTE"),
            candidate(337, "3.0", "TASTE"),
            candidate(338, "9.0", "DISCOVERY"),
            candidate(339, "8.0", "DISCOVERY"),
            candidate(340, "7.0", "DISCOVERY")
        };
    }

    /**
     * 프리페치 실행기가 빌 때까지 기다린다.
     *
     * <p>제출은 응답을 돌려주기 전에 끝나므로, 요청이 반환된 뒤 폴링해도 제출을 놓치지 않는다. 회차를 만들지 않는 경우에는 즉시 통과한다.
     */
    /** 프리페치 작업 1건이 끝날 때까지 기다린다. */
    private void awaitPrefetchIdle() throws InterruptedException {
        awaitPrefetchCompleted(completedPrefetchTasks + 1);
    }

    /**
     * 프리페치가 제출되지 않았음을 확인한다.
     *
     * <p>제출은 응답을 돌려주기 전에 끝나므로, 응답 뒤 짧게 기다려도 완료 수가 그대로면 제출 자체가 없었다는 뜻이다. 음수 단언이라 대기 없이 바로 보면 제출됐는데
     * 아직 실행 전인 경우를 놓친다.
     */
    private void awaitNoPrefetch() throws InterruptedException {
        ThreadPoolExecutor pool = prefetchExecutor.getThreadPoolExecutor();
        Thread.sleep(300);
        assertThat(pool.getActiveCount()).isZero();
        assertThat(pool.getQueue()).isEmpty();
        assertThat(pool.getCompletedTaskCount()).isEqualTo(completedPrefetchTasks);
    }

    /**
     * 프리페치 작업이 {@code expected}건 끝날 때까지 기다린다.
     *
     * <p>{@code getActiveCount()==0 && queue.isEmpty()}만으로는 부족하다. 코어 스레드 수에 도달하기 전의 작업은 큐를 거치지 않고 새
     * worker에게 바로 넘어가며, 그 worker가 자기 락을 잡기 전까지 활성 수는 0이다. 제출 직후 폴링하면 아직 시작도 안 한 작업을 끝났다고 볼 수 있다. 완료
     * 수는 그 창을 건너뛰지 않는다.
     */
    private void awaitPrefetchCompleted(long expected) throws InterruptedException {
        ThreadPoolExecutor pool = prefetchExecutor.getThreadPoolExecutor();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (pool.getCompletedTaskCount() >= expected
                    && pool.getActiveCount() == 0
                    && pool.getQueue().isEmpty()) {
                completedPrefetchTasks = pool.getCompletedTaskCount();
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException(
                "추천 프리페치가 제한 시간 안에 끝나지 않았습니다. 완료 "
                        + pool.getCompletedTaskCount()
                        + "건, 기대 "
                        + expected
                        + "건");
    }

    private ResultActions getRecommendations() throws Exception {
        return mvc.perform(get("/api/v1/recommendations").header("Authorization", bearer()));
    }

    private int concurrentGet(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return mvc.perform(get("/api/v1/recommendations").header("Authorization", bearer()))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private long nextSetId() {
        Long id =
                jdbc.queryForObject(
                        "SELECT coalesce(max(id),0)+1 FROM recommendation_sets", Long.class);
        return id == null ? 0 : id;
    }

    private int batchNoOf(long setId) {
        Integer batchNo =
                jdbc.queryForObject(
                        "SELECT batch_no FROM recommendation_sets WHERE id=?",
                        Integer.class,
                        setId);
        return batchNo == null ? -1 : batchNo;
    }

    private String generationOf(long setId) {
        return jdbc.queryForObject(
                "SELECT generation_id FROM recommendation_sets WHERE id=?", String.class, setId);
    }

    private int setCount() {
        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM recommendation_sets", Integer.class);
        return count == null ? 0 : count;
    }

    private int itemCount(long setId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM recommendation_items WHERE set_id=?",
                        Integer.class,
                        setId);
        return count == null ? 0 : count;
    }

    private int exposedItemCount(long setId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM recommendation_items"
                                + " WHERE set_id=? AND exposed_at IS NOT NULL",
                        Integer.class,
                        setId);
        return count == null ? 0 : count;
    }

    private String bearer() {
        return "Bearer " + tokens.createAccessToken(USER);
    }

    private void candidates(String generationId, RecommendationCandidate... items) {
        doReturn(candidateBatch(generationId, items)).when(candidateReader).read(USER);
    }

    private RecommendationCandidateBatch candidateBatch(
            String generationId, RecommendationCandidate... items) {
        return new RecommendationCandidateBatch(
                "model-v1",
                10L,
                generationId,
                Instant.parse("2026-09-20T00:00:00Z"),
                List.of(items));
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
                Math.toIntExact(id + 30000),
                "영화 " + id);
    }

    private void recommendationSet(long id, String generationId, int batchNo) {
        jdbc.update(
                "INSERT INTO recommendation_sets"
                        + "(id,user_id,input_version,model_version,candidate_version,"
                        + " generation_id,batch_no,generated_at)"
                        + " VALUES (?,?,'redis-candidate','model-v1',10,?,?,?)",
                id,
                USER,
                generationId,
                batchNo,
                OffsetDateTime.parse("2026-09-20T00:00:00Z"));
    }

    private void item(
            long id, long setId, long movieId, String type, int position, boolean exposed) {
        jdbc.update(
                "INSERT INTO recommendation_items"
                        + "(id,set_id,movie_id,type,position,reason,exposed_at)"
                        + " VALUES (?,?,?,?,?,CAST('{\"summary\":\"추천 이유\",\"factors\":[]}' AS jsonb),?)",
                id,
                setId,
                movieId,
                type,
                position,
                exposed ? OffsetDateTime.now() : null);
    }
}
