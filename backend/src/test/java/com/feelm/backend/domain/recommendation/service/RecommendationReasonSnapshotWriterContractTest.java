package com.feelm.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.party.repository.PartyRecommendationRepository;
import com.feelm.backend.domain.recommendation.repository.RecommendationBufferRepository;
import com.feelm.backend.domain.recommendation.repository.RecommendationBufferRepository.RecommendationBufferItem;
import com.feelm.backend.domain.recommendation.repository.RecommendationCandidateReader;
import com.feelm.backend.domain.recommendation.service.RecommendationReasonSnapshotWriter.ReasonSnapshot;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * S15P21E106-804. 추천 이유 보강이 항목마다 별도 커밋하는지, 그래서 서빙 조회와 잠금 순서가 반대여도 교착이 성립하지 않는지 실제 PostgreSQL 잠금으로
 * 확인한다.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@DisplayName("추천 이유 보강의 항목 단위 커밋과 서빙 잠금 순서")
class RecommendationReasonSnapshotWriterContractTest extends SharedPostgresContractTest {

    private static final long USER = 30;
    private static final long SET = 3000;
    private static final long TASTE_1 = 3001;
    private static final long TASTE_2 = 3002;
    private static final long DISCOVERY_1 = 3003;
    private static final String PENDING_REASON =
            "{\"summary\":\"임시 문구\",\"factors\":[],\"_reasonPending\":true}";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TokenProvider tokens;
    @Autowired RecommendationReasonSnapshotWriter writer;
    @Autowired RecommendationBufferRepository bufferRepository;
    @Autowired PartyRecommendationRepository partyRepository;
    @MockitoBean RecommendationCandidateReader candidateReader;

    private ExecutorService executor;
    private Connection holder;

    @BeforeEach
    void fixtures() {
        reset(candidateReader);
        when(candidateReader.read(anyLong()))
                .thenThrow(new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE));
        jdbc.execute(
                "TRUNCATE TABLE user_movie_dismissals, ratings, watch_records,"
                        + " movie_ott_providers, recommendation_items, recommendation_sets,"
                        + " party_members, parties, movies, users, genres, otts, popcorn_flavors"
                        + " RESTART IDENTITY CASCADE");
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                USER,
                "reason@example.test",
                "reason");
        for (long movieId = 201; movieId <= 204; movieId++) {
            jdbc.update(
                    "INSERT INTO movies(id,tmdb_id,title) VALUES (?,?,?)",
                    movieId,
                    Math.toIntExact(movieId + 20000),
                    "영화 " + movieId);
        }
        jdbc.update(
                "INSERT INTO recommendation_sets"
                        + "(id,user_id,input_version,model_version,candidate_version,"
                        + " generation_id,batch_no,generated_at)"
                        + " VALUES (?,?,'redis-candidate','model-v1',10,'generation-reason',0,?)",
                SET,
                USER,
                OffsetDateTime.parse("2026-09-20T00:00:00Z"));
        // 회차 생성과 같은 순서(TASTE 먼저)로 삽입해 id 순서가 TASTE → DISCOVERY가 되게 한다.
        item(TASTE_1, 201, "TASTE", 1, PENDING_REASON, null);
        item(TASTE_2, 202, "TASTE", 2, PENDING_REASON, null);
        item(DISCOVERY_1, 203, "DISCOVERY", 1, PENDING_REASON, null);
    }

    @AfterEach
    void cleanup() throws Exception {
        // 잠금 보유 연결을 먼저 풀어야 대기 중인 작업 스레드가 끝나고 executor가 종료된다. rollback이 던져도 close·종료는 수행한다.
        try {
            if (holder != null && !holder.isClosed()) {
                try {
                    holder.rollback();
                } finally {
                    holder.close();
                }
            }
        } finally {
            if (executor != null) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    @DisplayName("T1 첫 항목을 커밋한 뒤 다음 항목의 잠금을 기다린다 — 잠금을 한 번에 하나만 든다")
    void commitsEachItemBeforeWaitingOnTheNext() throws Exception {
        holdLock("SELECT id FROM recommendation_items WHERE id=" + TASTE_2 + " FOR UPDATE");
        executor = Executors.newSingleThreadExecutor();

        Future<?> completion =
                executor.submit(
                        () ->
                                writer.complete(
                                        List.of(
                                                snapshot(TASTE_1, "첫 항목 문구"),
                                                snapshot(TASTE_2, "둘째 항목 문구"))));

        // 둘째 항목에서 막힌 동안 첫 항목은 이미 커밋되어 다른 연결에서 보인다. 세트 단위 트랜잭션이었다면 보이지 않는다.
        awaitSummary(TASTE_1, "첫 항목 문구");
        assertThat(completion.isDone()).as("둘째 항목 잠금을 기다리는 중이어야 한다").isFalse();
        assertThat(summary(TASTE_2)).isEqualTo("임시 문구");

        releaseLock();
        completion.get(10, TimeUnit.SECONDS);
        assertThat(summary(TASTE_2)).isEqualTo("둘째 항목 문구");
        assertThat(pending(TASTE_1)).isNull();
        assertThat(pending(TASTE_2)).isNull();
    }

    @Test
    @DisplayName("T2 상위 트랜잭션 안에서 호출하면 즉시 실패하고 아무것도 바꾸지 않는다")
    void rejectsCallsInsideAnOuterTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status ->
                                                writer.complete(
                                                        List.of(snapshot(TASTE_1, "바뀌면 안 됨")))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(summary(TASTE_1)).isEqualTo("임시 문구");
    }

    @Test
    @DisplayName("T3 노출된 항목과 이미 확정된 항목은 건너뛰고 미확정 항목만 갱신한다")
    void skipsExposedAndAlreadyResolvedItems() {
        jdbc.update(
                "UPDATE recommendation_items SET exposed_at=? WHERE id=?",
                OffsetDateTime.now(),
                TASTE_1);
        jdbc.update(
                "UPDATE recommendation_items SET reason="
                        + "CAST('{\"summary\":\"확정 문구\",\"factors\":[]}' AS jsonb) WHERE id=?",
                TASTE_2);

        writer.complete(
                List.of(
                        snapshot(TASTE_1, "새 문구"),
                        snapshot(TASTE_2, "새 문구"),
                        snapshot(DISCOVERY_1, "새 문구")));

        assertThat(summary(TASTE_1)).isEqualTo("임시 문구");
        assertThat(summary(TASTE_2)).isEqualTo("확정 문구");
        assertThat(summary(DISCOVERY_1)).isEqualTo("새 문구");
        assertThat(pending(DISCOVERY_1)).isNull();
    }

    @Test
    @DisplayName("확장 reason 필드를 한 UPDATE로 교체하고 pending을 제거한다")
    void completesFullSnapshotPerItemWithoutChangingLockBoundary() {
        String usedEvidenceIds = "[\"sha256:" + "a".repeat(64) + "\"]";

        writer.complete(
                List.of(
                        new ReasonSnapshot(
                                TASTE_1, "장르 취향 패턴을 바탕으로 골랐어요.", "[]", usedEvidenceIds)));

        assertThat(summary(TASTE_1)).isEqualTo("장르 취향 패턴을 바탕으로 골랐어요.");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT cast(reason->'usedEvidenceIds' AS text)"
                                        + " FROM recommendation_items WHERE id=?",
                                String.class,
                                TASTE_1))
                .isEqualTo(usedEvidenceIds);
        assertThat(pending(TASTE_1)).isNull();
    }

    @Test
    @DisplayName("T4 보강이 TASTE 항목을 든 채 조회가 들어와도 교착 없이 대기 후 200을 돌려준다")
    void servingReadDoesNotDeadlockWhileEnrichmentHoldsAnItem() throws Exception {
        // 수정 전 보강이 첫 항목(TASTE 1)을 갱신한 채 멈춰 있던 상태를 재현한다.
        holdLock(
                "UPDATE recommendation_items SET reason=reason ||"
                        + " CAST('{\"summary\":\"보강 중\"}' AS jsonb) WHERE id="
                        + TASTE_1);
        executor = Executors.newSingleThreadExecutor();

        Future<String> response =
                executor.submit(
                        () ->
                                mvc.perform(
                                                get("/api/v1/recommendations")
                                                        .header(
                                                                "Authorization",
                                                                "Bearer "
                                                                        + tokens.createAccessToken(
                                                                                USER)))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString());

        // 조회는 DISCOVERY 1을 잠근 뒤 TASTE 1에서 대기한다. 보강은 더 이상 다른 잠금을 요구하지 않으므로 순환이 없다.
        awaitLockWaiter();
        assertThat(response.isDone()).as("조회가 보강이 든 행에서 대기 중이어야 한다").isFalse();

        releaseLock();
        String body = response.get(10, TimeUnit.SECONDS);
        assertThat(body).contains("\"recommendationSetId\":" + SET);
        assertThat(body.split("\"recommendationItemId\"").length - 1).isEqualTo(3);
        assertThat(exposedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("T4-1 잠금 경합이 없으면 조회는 그대로 200이다")
    void servingReadSucceedsWithoutContention() throws Exception {
        mvc.perform(
                        get("/api/v1/recommendations")
                                .header(
                                        "Authorization",
                                        "Bearer " + tokens.createAccessToken(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    @DisplayName("T7 온보딩이 낮은 id 영화를 FOR UPDATE로 든 채 조회가 들어와도 노출 이력 INSERT가 같은 방향으로 잠가 교착이 없다")
    void exposureHistoryLocksMoviesInAscendingOrderLikeOnboarding() throws Exception {
        // 카드 순서는 영화 202 → 201(TASTE) → 203(DISCOVERY)로, id 오름차순과 반대다.
        // UK(set_id, movie_id) 때문에 임시 영화 204를 거쳐 두 항목의 영화를 맞바꾼다.
        jdbc.update("UPDATE recommendation_items SET movie_id=204 WHERE id=?", TASTE_1);
        jdbc.update("UPDATE recommendation_items SET movie_id=201 WHERE id=?", TASTE_2);
        jdbc.update("UPDATE recommendation_items SET movie_id=202 WHERE id=?", TASTE_1);
        // 온보딩(API-024)이 영화 201을 잠근 상태를 재현한다. 온보딩은 id 오름차순으로 202를 다음에 잠근다.
        holdLock("SELECT id FROM movies WHERE id=201 FOR UPDATE");
        executor = Executors.newSingleThreadExecutor();

        Future<String> response = executor.submit(this::getRecommendations);
        awaitLockWaiter();
        assertThat(response.isDone()).as("조회가 영화 201의 KEY SHARE에서 대기 중이어야 한다").isFalse();

        // 조회가 201부터 요청했으면 202에는 아직 KEY SHARE가 없어 온보딩의 다음 잠금이 바로 잡힌다. 카드 순서(202 먼저)대로
        // 걸었다면 여기서 서로를 기다리는 순환이 생겨 lock_timeout 또는 deadlock 감지로 실패한다.
        try (Statement statement = holder.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '3s'");
            statement.execute("SELECT id FROM movies WHERE id=202 FOR UPDATE");
        }

        releaseLock();
        String body = response.get(10, TimeUnit.SECONDS);
        assertThat(body).contains("\"recommendationSetId\":" + SET);
        assertThat(exposedCount()).isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM user_movie_exposures WHERE user_id=?",
                                Long.class,
                                USER))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("T8 회차 항목 INSERT는 movie_id 오름차순으로 실행되고 position은 그대로 보존된다")
    void bufferItemsAreInsertedInAscendingMovieIdOrder() {
        long setId = 3100;
        jdbc.update(
                "INSERT INTO recommendation_sets"
                        + "(id,user_id,input_version,model_version,candidate_version,"
                        + " generation_id,batch_no,generated_at)"
                        + " VALUES (?,?,'redis-candidate','model-v1',10,'generation-order',1,?)",
                setId,
                USER,
                OffsetDateTime.parse("2026-09-20T00:00:00Z"));

        bufferRepository.insertItems(
                setId,
                List.of(
                        bufferItem(203, "TASTE", 1),
                        bufferItem(201, "TASTE", 2),
                        bufferItem(202, "DISCOVERY", 1)));

        assertThat(movieIdsByInsertOrder(setId)).containsExactly(201L, 202L, 203L);
        assertThat(positionsByMovie(setId)).containsExactly(2, 1, 1);
    }

    @Test
    @DisplayName("T9 파티 항목 INSERT도 movie_id 오름차순으로 실행되고 position은 그대로 보존된다")
    void partyItemsAreInsertedInAscendingMovieIdOrder() {
        Long partyId =
                jdbc.queryForObject(
                        "INSERT INTO parties(name,owner_id,status,created_at)"
                                + " VALUES ('party',?,'ACTIVE',?) RETURNING id",
                        Long.class,
                        USER,
                        OffsetDateTime.parse("2026-09-20T00:00:00Z"));
        long setId =
                partyRepository.insertSet(
                        partyId, "input-order", "v1", OffsetDateTime.parse("2026-09-20T00:00:00Z"));

        List<Long> ids =
                partyRepository.insertItems(
                        setId,
                        List.of(
                                new PartyRecommendationRepository.NewItem(203, 1),
                                new PartyRecommendationRepository.NewItem(201, 2),
                                new PartyRecommendationRepository.NewItem(202, 3)));

        assertThat(ids).isSorted();
        assertThat(movieIdsByInsertOrder(setId)).containsExactly(201L, 202L, 203L);
        assertThat(positionsByMovie(setId)).containsExactly(2, 3, 1);
    }

    @Test
    @DisplayName("T10 빈 슬롯 보충이 여러 영화를 순차로 노출해도 이력 기록은 한 번에 movie_id 오름차순으로 잠근다")
    void replenishmentLocksMoviesInAscendingOrderAcrossMultipleExposures() throws Exception {
        // 이미 노출된 TASTE 1편만 있어 보충이 TASTE 1편·DISCOVERY 1편을 순서대로 채운다. 호출 순서는
        // TASTE(movie 202) → DISCOVERY(movie 201)로 id 내림차순이다 — 슬롯마다 이력을 나눠 기록하면(정렬 전 코드)
        // 온보딩과 반대 방향으로 movies를 잠근다.
        jdbc.update("DELETE FROM recommendation_items WHERE set_id=?", SET);
        item(9001, 203, "TASTE", 1, PENDING_REASON, OffsetDateTime.now());
        item(9002, 202, "TASTE", 2, PENDING_REASON, null);
        item(9003, 201, "DISCOVERY", 1, PENDING_REASON, null);

        // 온보딩(API-024)이 movie 201을 먼저 잠근 상태를 재현한다. 온보딩은 다음으로 202를 오름차순으로 잠근다.
        holdLock("SELECT id FROM movies WHERE id=201 FOR UPDATE");
        executor = Executors.newSingleThreadExecutor();

        Future<String> response = executor.submit(this::getRecommendations);
        awaitLockWaiter();
        assertThat(response.isDone()).as("보충이 movie 201의 KEY SHARE에서 대기 중이어야 한다").isFalse();

        // 보충이 201부터 요청했으면 202에는 아직 KEY SHARE가 없어 온보딩의 다음 잠금이 바로 잡힌다. 슬롯마다
        // 이력을 나눠 202→201 순으로 걸었다면 여기서 순환이 생겨 lock_timeout으로 실패한다.
        try (Statement statement = holder.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '3s'");
            statement.execute("SELECT id FROM movies WHERE id=202 FOR UPDATE");
        }

        releaseLock();
        String body = response.get(10, TimeUnit.SECONDS);
        assertThat(body).contains("\"recommendationSetId\":" + SET);
        // 셋 다(기존 노출 1 + 보충 2) 노출 상태가 된다.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM recommendation_items"
                                        + " WHERE set_id=? AND exposed_at IS NOT NULL",
                                Long.class,
                                SET))
                .isEqualTo(3);
        // 이력은 이번 요청이 새로 노출한 2편(202, 201)만 기록한다. 203은 픽스처가 exposed_at을 직접 심어
        // recordExposureHistory를 거치지 않았으므로 이력이 없는 것이 맞다.
        assertThat(
                        jdbc.queryForList(
                                "SELECT movie_id FROM user_movie_exposures"
                                        + " WHERE user_id=? ORDER BY movie_id",
                                Long.class,
                                USER))
                .containsExactly(201L, 202L);
    }

    private String getRecommendations() throws Exception {
        return mvc.perform(
                        get("/api/v1/recommendations")
                                .header(
                                        "Authorization",
                                        "Bearer " + tokens.createAccessToken(USER)))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static RecommendationBufferItem bufferItem(long movieId, String type, int position) {
        return new RecommendationBufferItem(
                movieId, type, null, JsonNodeFactory.instance.objectNode(), position);
    }

    /** 삽입 순서(id 순)로 movie_id를 돌려준다. */
    private List<Long> movieIdsByInsertOrder(long setId) {
        return jdbc.queryForList(
                "SELECT movie_id FROM recommendation_items WHERE set_id=? ORDER BY id",
                Long.class,
                setId);
    }

    /** movie_id 오름차순(201, 202, 203)으로 position을 돌려준다. */
    private List<Integer> positionsByMovie(long setId) {
        return jdbc.queryForList(
                "SELECT position FROM recommendation_items WHERE set_id=? ORDER BY movie_id",
                Integer.class,
                setId);
    }

    private void holdLock(String sql) throws SQLException {
        holder = dataSource.getConnection();
        holder.setAutoCommit(false);
        try (Statement statement = holder.createStatement()) {
            statement.execute(sql);
        }
    }

    private void releaseLock() throws SQLException {
        holder.commit();
        holder.close();
    }

    /** 다른 백엔드가 행 잠금을 기다리는 상태를 pg_stat_activity로 관측한다. sleep 기반 추정보다 결정적이다. */
    private void awaitLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Long waiters =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity"
                                    + " WHERE datname = current_database()"
                                    + " AND wait_event_type = 'Lock'",
                            Long.class);
            if (waiters != null && waiters > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("조회가 5초 안에 행 잠금 대기 상태로 관측되지 않았다.");
    }

    private void awaitSummary(long itemId, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (expected.equals(summary(itemId))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("항목 " + itemId + "의 문구가 5초 안에 커밋되지 않았다: " + summary(itemId));
    }

    private static ReasonSnapshot snapshot(long itemId, String summary) {
        return new ReasonSnapshot(itemId, summary, "[]");
    }

    private void item(
            long id, long movieId, String type, int position, String reason, OffsetDateTime at) {
        jdbc.update(
                "INSERT INTO recommendation_items"
                        + "(id,set_id,movie_id,type,position,reason,exposed_at)"
                        + " VALUES (?,?,?,?,?,CAST(? AS jsonb),?)",
                id,
                SET,
                movieId,
                type,
                position,
                reason,
                at);
    }

    private String summary(long itemId) {
        return jdbc.queryForObject(
                "SELECT reason->>'summary' FROM recommendation_items WHERE id=?",
                String.class,
                itemId);
    }

    private String pending(long itemId) {
        return jdbc.queryForObject(
                "SELECT reason->>'_reasonPending' FROM recommendation_items WHERE id=?",
                String.class,
                itemId);
    }

    private long exposedCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM recommendation_items WHERE exposed_at IS NOT NULL",
                Long.class);
    }
}
