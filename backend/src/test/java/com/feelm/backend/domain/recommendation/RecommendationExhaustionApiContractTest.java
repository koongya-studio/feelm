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
import com.feelm.backend.domain.recommendation.service.RecommendationBufferService;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.task.scheduling.enabled=false"
        })
@AutoConfigureMockMvc
@DisplayName("API-045 추천 버퍼 소진 감지와 다음 회차 생성")
class RecommendationExhaustionApiContractTest extends SharedPostgresContractTest {

    private static final long USER = 30;
    private static final long SET = 3000;
    private static final long FLAVOR = 1;
    private static final String GENERATION = "generation-exhaustion";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired RecommendationBufferService bufferService;
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
        user(USER, "exhaustion");
        for (long movieId = 201; movieId <= 220; movieId++) {
            movie(movieId);
        }
        recommendationSet(SET, GENERATION, 0);
        item(3001, 201, "TASTE", 1, true);
        item(3002, 202, "TASTE", 2, true);
        item(3003, 205, "DISCOVERY", 1, true);
        // 명시 ID로 넣은 고정 데이터 뒤에 새 세트·아이템이 더 큰 ID를 받도록 시퀀스를 맞춘다.
        jdbc.execute("SELECT setval(pg_get_serial_sequence('recommendation_sets','id'),3000,true)");
        jdbc.execute(
                "SELECT setval(pg_get_serial_sequence('recommendation_items','id'),3100,true)");
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
    @DisplayName("T1 유효 카드와 미노출 후보가 모두 없으면 같은 generation의 다음 회차를 만든다")
    void createsNextBatchWhenBufferIsExhausted() throws Exception {
        exhaustCurrentBatch();
        candidates(
                GENERATION,
                candidate(206, "5.0", "TASTE"),
                candidate(207, "4.0", "TASTE"),
                candidate(209, "3.0", "TASTE"),
                candidate(208, "5.0", "DISCOVERY"),
                candidate(210, "4.0", "DISCOVERY"),
                candidate(211, "3.0", "DISCOVERY"));
        long nextSetId = nextSetId();

        getRecommendations()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationSetId").value(nextSetId))
                .andExpect(jsonPath("$.items.length()").value(6));

        assertThat(batchNoOf(nextSetId)).isEqualTo(1);
    }

    @Test
    @DisplayName("T2 다음 회차는 평가를 철회해 다시 유효해진 이전 회차 영화도 담지 않는다")
    void nextBatchExcludesMoviesFromPreviousBatches() throws Exception {
        exhaustCurrentBatch();
        candidates(
                GENERATION,
                candidate(206, "5.0", "TASTE"),
                candidate(207, "4.0", "TASTE"),
                candidate(208, "3.0", "DISCOVERY"));
        getRecommendations().andExpect(status().isOk());

        // 1회차 영화의 평가를 철회하면 그 영화는 다시 유효해진다. 제외 조건이 없으면 2회차에 재등장한다.
        jdbc.update("DELETE FROM ratings WHERE user_id=? AND movie_id=201", USER);
        exhaustNextBatch();
        candidates(
                GENERATION,
                candidate(201, "9.0", "TASTE"),
                candidate(209, "5.0", "TASTE"),
                candidate(210, "4.0", "DISCOVERY"));

        getRecommendations().andExpect(status().isOk());

        List<Long> secondBatchMovieIds =
                jdbc.queryForList(
                        "SELECT i.movie_id FROM recommendation_items i"
                                + " JOIN recommendation_sets s ON s.id=i.set_id"
                                + " WHERE s.batch_no=2 ORDER BY i.movie_id",
                        Long.class);
        assertThat(secondBatchMovieIds)
                .doesNotContain(201L, 202L, 205L, 206L, 207L, 208L)
                .contains(209L, 210L);
    }

    @Test
    @DisplayName("T3 소진이 아니면 Redis 후보를 읽지 않는다")
    void doesNotTouchRedisWhenNotExhausted() throws Exception {
        getRecommendations()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));

        verify(candidateReader, never()).read(USER);
        assertThat(setCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T5-1 유효 카드가 남아 있으면 다음 회차를 만들지 않는다")
    void doesNotCreateNextBatchWhileCardsRemain() throws Exception {
        getRecommendations().andExpect(status().isOk());
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (?,201,5)", USER);

        getRecommendations()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationSetId").value(SET))
                .andExpect(jsonPath("$.items.length()").value(2));

        verify(candidateReader, never()).read(USER);
        assertThat(setCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T4·T10-1 Redis 후보를 쓸 수 없으면 준비 중 오류를 반환하고 상태를 바꾸지 않는다")
    void returnsPreparingWhenCandidatesAreUnavailable() throws Exception {
        exhaustCurrentBatch();

        getRecommendations()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECOMMENDATION_PREPARING"));

        assertThat(setCount()).isEqualTo(1);
        assertThat(exposedItemCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("T6 소진된 회차의 노출 이력은 보존한다")
    void keepsHistoryOfExhaustedBatch() throws Exception {
        exhaustCurrentBatch();
        candidates(
                GENERATION,
                candidate(206, "5.0", "TASTE"),
                candidate(207, "4.0", "TASTE"),
                candidate(208, "3.0", "DISCOVERY"));

        getRecommendations().andExpect(status().isOk());

        Integer previouslyExposed =
                jdbc.queryForObject(
                        "SELECT count(*) FROM recommendation_items"
                                + " WHERE set_id=? AND exposed_at IS NOT NULL",
                        Integer.class,
                        SET);
        assertThat(previouslyExposed).isEqualTo(3);
    }

    @Test
    @DisplayName("T7 두 요청이 모두 소진으로 판정해도 다음 회차는 한 번만 늘어난다")
    void concurrentReadsCreateSingleNextBatch() throws Exception {
        exhaustCurrentBatch();
        // Redis 읽기를 배리어로 묶어 두 요청이 모두 소진 판정을 마친 뒤 회차 생성에 들어가게 한다.
        // 후보 읽기가 사용자 행 잠금 안에서 일어나면 먼저 들어간 요청이 잠금을 쥔 채 배리어에서 멈추고
        // 다른 요청이 잠금 대기에 걸려, 이 테스트는 배리어 시간 초과로 실패한다.
        CyclicBarrier bothJudgedExhausted = new CyclicBarrier(2);
        candidatesAfter(
                bothJudgedExhausted,
                GENERATION,
                candidate(206, "5.0", "TASTE"),
                candidate(207, "4.0", "TASTE"),
                candidate(208, "3.0", "DISCOVERY"));
        long nextSetId = nextSetId();

        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> first = executor.submit(() -> concurrentGet(ready, start));
        Future<String> second = executor.submit(() -> concurrentGet(ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        String firstBody = first.get(15, TimeUnit.SECONDS);
        String secondBody = second.get(15, TimeUnit.SECONDS);
        assertThat(firstBody).contains("\"recommendationSetId\":" + nextSetId);
        assertThat(secondBody).contains("\"recommendationSetId\":" + nextSetId);
        // 둘 중 하나가 먼저 노출을 끝내도 나머지 요청이 빈 목록을 돌려주지 않는다.
        assertThat(itemCount(firstBody)).isEqualTo(3);
        assertThat(itemCount(secondBody)).isEqualTo(3);
        verify(candidateReader, times(2)).read(USER);
        assertThat(setCount()).isEqualTo(2);
        assertThat(batchNoOf(nextSetId)).isEqualTo(1);
    }

    @Test
    @DisplayName("T7-1 다른 요청이 이미 다음 회차를 만들었으면 재생성하지 않는다")
    void skipsRegenerationWhenNextBatchAlreadyExists() {
        exhaustCurrentBatch();
        candidates(
                GENERATION,
                candidate(206, "5.0", "TASTE"),
                candidate(207, "4.0", "TASTE"),
                candidate(208, "3.0", "DISCOVERY"));
        long created = bufferService.createNextBatch(USER, SET).recommendationSetId();

        long repeated = bufferService.createNextBatch(USER, SET).recommendationSetId();

        assertThat(repeated).isEqualTo(created);
        assertThat(setCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("T8 같은 generation의 알림 재수신은 첫 회차를 재사용한다")
    void noticePathReusesFirstBatch() {
        candidates(
                GENERATION,
                candidate(206, "5.0", "TASTE"),
                candidate(207, "4.0", "TASTE"),
                candidate(208, "3.0", "DISCOVERY"));

        long first = bufferService.createBufferFromNotice(USER).recommendationSetId();
        long second = bufferService.createBufferFromNotice(USER).recommendationSetId();

        assertThat(first).isEqualTo(SET);
        assertThat(second).isEqualTo(first);
        assertThat(setCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T9 같은 generation에 회차가 여럿이면 알림 경로는 최신 회차를 재사용한다")
    void noticePathReusesLatestBatch() {
        exhaustCurrentBatch();
        candidates(
                GENERATION,
                candidate(206, "5.0", "TASTE"),
                candidate(207, "4.0", "TASTE"),
                candidate(208, "3.0", "DISCOVERY"));
        long nextSetId = bufferService.createNextBatch(USER, SET).recommendationSetId();

        long reused = bufferService.createBufferFromNotice(USER).recommendationSetId();

        assertThat(batchNoOf(nextSetId)).isEqualTo(1);
        assertThat(reused).isEqualTo(nextSetId);
        assertThat(setCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("T12 소진 판정 뒤 카드가 되살아나면 다음 회차를 만들지 않는다")
    void doesNotCreateNextBatchWhenCardsRevivedAfterJudgement() {
        exhaustCurrentBatch();
        candidates(
                GENERATION,
                candidate(206, "5.0", "TASTE"),
                candidate(207, "4.0", "TASTE"),
                candidate(208, "3.0", "DISCOVERY"));
        // 조회가 소진으로 판정해 커밋한 뒤, 재생성 전에 평가가 철회된 상태다.
        jdbc.update("DELETE FROM ratings WHERE user_id=? AND movie_id=201", USER);

        long served = bufferService.createNextBatch(USER, SET).recommendationSetId();

        assertThat(served).isEqualTo(SET);
        assertThat(setCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T13 재생성 중 계정이 비활성화되면 준비 중이 아니라 401을 반환한다")
    void propagatesUnauthorizedWhenAccountIsDeactivatedDuringRegeneration() throws Exception {
        exhaustCurrentBatch();
        candidatesAfter(
                () -> jdbc.update("UPDATE users SET status='WITHDRAWN' WHERE id=?", USER),
                GENERATION,
                candidate(206, "5.0", "TASTE"),
                candidate(207, "4.0", "TASTE"),
                candidate(208, "3.0", "DISCOVERY"));

        getRecommendations()
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(setCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T14 다음 회차 카드도 저장 전에 확정한 근거 문구로 노출한다")
    void nextBatchExposesGroundedReason() throws Exception {
        genre("ACTION", "액션");
        exhaustCurrentBatch();
        candidates(
                GENERATION,
                sharedGenreCandidate(206, "5.0", "TASTE"),
                sharedGenreCandidate(207, "4.0", "TASTE"),
                sharedGenreCandidate(209, "3.0", "TASTE"),
                sharedGenreCandidate(208, "5.0", "DISCOVERY"),
                sharedGenreCandidate(210, "4.0", "DISCOVERY"),
                sharedGenreCandidate(211, "3.0", "DISCOVERY"));

        getRecommendations()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reason.summary").value("좋아하신 영화와 액션 장르가 같아요."));
    }

    @Test
    @DisplayName("T10 최초 노출은 목표 구성을 못 채워도 확보한 만큼 노출한다")
    void exposesPartiallyFilledInitialSet() throws Exception {
        jdbc.update("DELETE FROM recommendation_items WHERE set_id=?", SET);
        item(3010, 203, "TASTE", 1, false);
        item(3011, 206, "DISCOVERY", 1, false);

        getRecommendations()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        assertThat(exposedItemCount()).isEqualTo(2);
    }

    private void exhaustCurrentBatch() {
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (?,201,5)", USER);
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (?,202,4)", USER);
        jdbc.update("INSERT INTO user_movie_dismissals(user_id,movie_id) VALUES (?,205)", USER);
    }

    /** 1회차 뒤에 만들어진 회차(206·207·208)를 모두 무효로 만든다. */
    private void exhaustNextBatch() {
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (?,206,5)", USER);
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (?,207,4)", USER);
        jdbc.update("INSERT INTO user_movie_dismissals(user_id,movie_id) VALUES (?,208)", USER);
    }

    private ResultActions getRecommendations() throws Exception {
        return mvc.perform(
                get("/api/v1/recommendations")
                        .param("size", "6")
                        .header("Authorization", bearer()));
    }

    private String concurrentGet(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return mvc.perform(
                        get("/api/v1/recommendations")
                                .param("size", "6")
                                .header("Authorization", bearer()))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private int itemCount(String body) {
        return new tools.jackson.databind.ObjectMapper().readTree(body).path("items").size();
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

    private int setCount() {
        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM recommendation_sets", Integer.class);
        return count == null ? 0 : count;
    }

    private int exposedItemCount() {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM recommendation_items WHERE exposed_at IS NOT NULL",
                        Integer.class);
        return count == null ? 0 : count;
    }

    private String bearer() {
        return "Bearer " + tokens.createAccessToken(USER);
    }

    /** 두 요청이 모두 소진 판정을 마친 뒤에야 후보를 돌려준다. */
    private void candidatesAfter(
            CyclicBarrier barrier, String generationId, RecommendationCandidate... items) {
        candidatesAfter(
                () -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                        throw new IllegalStateException("동시 요청이 소진 판정 지점에 모이지 않았습니다.", e);
                    }
                },
                generationId,
                items);
    }

    /** 후보를 돌려주기 직전에 상태를 바꿔, 소진 판정과 재생성 사이의 창을 재현한다. */
    private void candidatesAfter(
            Runnable beforeRead, String generationId, RecommendationCandidate... items) {
        RecommendationCandidateBatch batch =
                new RecommendationCandidateBatch(
                        "model-v1",
                        10L,
                        generationId,
                        Instant.parse("2026-09-20T00:00:00Z"),
                        List.of(items));
        doAnswer(
                        invocation -> {
                            beforeRead.run();
                            return batch;
                        })
                .when(candidateReader)
                .read(USER);
    }

    private void candidates(String generationId, RecommendationCandidate... items) {
        doReturn(
                        new RecommendationCandidateBatch(
                                "model-v1",
                                10L,
                                generationId,
                                Instant.parse("2026-09-20T00:00:00Z"),
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

    /** 장르 근거가 확인되는 후보다. 저장 전에 확정할 수 있는 문구를 가진다. */
    private RecommendationCandidate sharedGenreCandidate(long movieId, String score, String type) {
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        tools.jackson.databind.node.ObjectNode reason = mapper.createObjectNode();
        tools.jackson.databind.node.ObjectNode entry = mapper.createObjectNode();
        entry.put("code", "SHARED_GENRE");
        entry.putArray("sourceMovieIds").add(201L);
        entry.putArray("matchedGenreCodes").add("ACTION");
        reason.putArray("reasons").add(entry);
        return new RecommendationCandidate(movieId, new BigDecimal(score), type, null, reason);
    }

    private void genre(String code, String name) {
        jdbc.update("INSERT INTO genres(code,name,tmdb_genre_id) VALUES (?,?,?)", code, name, 28);
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
                Math.toIntExact(id + 20000),
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

    private void item(long id, long movieId, String type, int position, boolean exposed) {
        jdbc.update(
                "INSERT INTO recommendation_items"
                        + "(id,set_id,movie_id,type,position,reason,exposed_at)"
                        + " VALUES (?,?,?,?,?,CAST('{\"summary\":\"추천 이유\",\"factors\":[]}' AS jsonb),?)",
                id,
                SET,
                movieId,
                type,
                position,
                exposed ? OffsetDateTime.now() : null);
    }
}
