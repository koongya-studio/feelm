package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.recommendation.model.RecommendationBufferResult;
import com.feelm.backend.domain.recommendation.model.RecommendationCandidate;
import com.feelm.backend.domain.recommendation.model.RecommendationCandidateBatch;
import com.feelm.backend.domain.recommendation.repository.RecommendationBufferRepository;
import com.feelm.backend.domain.recommendation.repository.RecommendationCandidateReader;
import com.feelm.backend.domain.recommendation.service.RecommendationBufferService;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.task.scheduling.enabled=false"
        })
@DisplayName("개인 추천 버퍼 생성")
class RecommendationBufferServiceTest extends SharedPostgresContractTest {

    private static final long USER = 10;
    private static final Instant UPDATED_AT = Instant.parse("2026-09-14T01:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired RecommendationBufferService service;
    @MockitoBean RecommendationCandidateReader candidateReader;
    @MockitoSpyBean RecommendationBufferRepository bufferRepository;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        reset(candidateReader);
        jdbc.execute(
                "TRUNCATE TABLE outbox_events, user_movie_dismissals, ratings, watch_records,"
                        + " user_otts, movie_ott_providers, recommendation_items,"
                        + " recommendation_sets, movies, otts, users RESTART IDENTITY CASCADE");
        user(USER);
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
    @DisplayName("T5 서비스에 없거나 평가·관심없음 처리한 영화를 버퍼에서 제외한다")
    void filtersMissingRatedAndDismissedMovies() {
        movies(100, 102, 103, 104);
        rating(USER, 102);
        watchRecord(USER, 103, null, "WATCHED_UNRATED");
        dismissal(USER, 104);
        candidates(
                candidate(100, "5.0", "TASTE", null),
                candidate(101, "4.9", "TASTE", null),
                candidate(102, "4.8", "TASTE", null),
                candidate(103, "4.7", "TASTE", null),
                candidate(104, "4.6", "TASTE", null));

        RecommendationBufferResult result = service.createBuffer(USER);

        assertThat(storedMovieIds(result.recommendationSetId())).containsExactly(100L, 103L);
        verify(bufferRepository, times(1)).findEligibleMovieIds(eq(USER), anyList());
    }

    @Test
    @DisplayName("T5 OTT 링크를 누른 영화도 추천시스템 점수에 따라 포함한다")
    void keepsOttLinkClickedMovie() {
        movies(100, 101);
        ott(1, "NETFLIX");
        watchRecord(USER, 101, 1L, "LINK_CLICKED");
        candidates(candidate(100, "5.0", "TASTE", null), candidate(101, "4.9", "TASTE", null));

        RecommendationBufferResult result = service.createBuffer(USER);

        assertThat(storedMovieIds(result.recommendationSetId())).containsExactly(100L, 101L);
    }

    @Test
    @DisplayName("T6 TASTE와 DISCOVERY를 각각 점수 순서의 독립 버퍼로 저장한다")
    void ranksAndStoresIndependentBuffersByType() {
        movies(100, 101, 102, 200, 201);
        candidates(
                candidate(102, "3.0", "TASTE", null),
                candidate(101, "4.0", "TASTE", null),
                candidate(100, "5.0", "TASTE", null),
                candidate(201, "3.0", "DISCOVERY", "FLAVOR_B"),
                candidate(200, "4.0", "DISCOVERY", "FLAVOR_A"));

        RecommendationBufferResult result = service.createBuffer(USER);

        assertThat(storedMovieIds(result.recommendationSetId(), "TASTE"))
                .containsExactly(100L, 101L, 102L);
        assertThat(storedMovieIds(result.recommendationSetId(), "DISCOVERY"))
                .containsExactly(200L, 201L);
    }

    @Test
    @DisplayName("T6 같은 generation의 동점 후보는 영화 ID 순서가 아닌 동일한 순서로 재현된다")
    void deterministicallyShufflesEqualScores() {
        movies(100, 101, 102, 103, 104, 105);
        candidates(
                candidate(100, "4.0", "TASTE", null),
                candidate(101, "4.0", "TASTE", null),
                candidate(102, "4.0", "TASTE", null),
                candidate(103, "4.0", "TASTE", null),
                candidate(104, "4.0", "TASTE", null),
                candidate(105, "4.0", "TASTE", null));

        long firstSet = service.createBuffer(USER).recommendationSetId();
        List<Long> firstOrder = storedMovieIds(firstSet, "TASTE");
        jdbc.update("DELETE FROM recommendation_sets WHERE id=?", firstSet);
        long recreatedSet = service.createBuffer(USER).recommendationSetId();

        assertThat(firstOrder).isNotEqualTo(List.of(100L, 101L, 102L, 103L, 104L, 105L));
        assertThat(storedMovieIds(recreatedSet, "TASTE")).containsExactlyElementsOf(firstOrder);
    }

    @Test
    @DisplayName("T7 종류별 후보를 최대 20편씩, 부족하면 있는 만큼만 저장한다")
    void storesAtMostTwentyPerTypeWithoutPadding() {
        List<RecommendationCandidate> many = new ArrayList<>();
        for (long movieId = 1; movieId <= 25; movieId++) {
            movie(movieId);
            many.add(candidate(movieId, Integer.toString(100 - (int) movieId), "TASTE", null));
            long discoveryMovieId = movieId + 100;
            movie(discoveryMovieId);
            many.add(
                    candidate(
                            discoveryMovieId,
                            Integer.toString(100 - (int) movieId),
                            "DISCOVERY",
                            "FLAVOR_A"));
        }
        candidates(many.toArray(RecommendationCandidate[]::new));

        RecommendationBufferResult first = service.createBuffer(USER);

        assertThat(storedMovieIds(first.recommendationSetId(), "TASTE")).hasSize(20);
        assertThat(storedMovieIds(first.recommendationSetId(), "DISCOVERY")).hasSize(20);
        assertThat(storedPositions(first.recommendationSetId(), "TASTE"))
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, 20).boxed().toList());
        assertThat(storedPositions(first.recommendationSetId(), "DISCOVERY"))
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, 20).boxed().toList());

        candidates(4L, candidate(24, "5.0", "TASTE", null), candidate(25, "4.0", "TASTE", null));
        RecommendationBufferResult second = service.createBuffer(USER);

        assertThat(storedMovieIds(second.recommendationSetId())).containsExactly(24L, 25L);
        assertThat(storedPositions(second.recommendationSetId())).containsExactly(1, 2);
    }

    @Test
    @DisplayName("T8 추천 종류·맛과 추천시스템 버전을 함께 저장한다")
    void preservesTypeFlavorAndModelMetadata() {
        movies(100, 101);
        candidates(
                candidate(
                        100,
                        "5.0",
                        "TASTE",
                        null,
                        """
                        {"reasons":[{"code":"SHARED_FEATURES_WITH_LIKED_MOVIE",\
                        "sourceMovieId":55,"sharedFeatures":{"tmdbGenreIds":[878],\
                        "tmdbDirectorIds":[],"tmdbCastIds":[],"tmdbKeywordIds":[]}}]}
                        """),
                candidate(101, "4.0", "DISCOVERY", "FLAVOR_A"));

        RecommendationBufferResult result = service.createBuffer(USER);

        assertThat(
                        jdbc.queryForMap(
                                "SELECT input_version,model_version,candidate_version,generation_id"
                                        + " FROM recommendation_sets WHERE id=?",
                                result.recommendationSetId()))
                .containsEntry("input_version", "redis-candidate")
                .containsEntry("model_version", "als-serving-example")
                .containsEntry("candidate_version", 3L)
                .containsEntry("generation_id", "generation-3");
        List<java.util.Map<String, Object>> items =
                jdbc.queryForList(
                        "SELECT type,flavor_code,reason::text AS reason FROM recommendation_items"
                                + " WHERE set_id=?"
                                + " ORDER BY CASE type WHEN 'TASTE' THEN 1 ELSE 2 END, position",
                        result.recommendationSetId());
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("type", "TASTE").containsEntry("flavor_code", null);
        assertThat(items.get(0).get("reason").toString())
                .contains("SHARED_FEATURES_WITH_LIKED_MOVIE", "sourceMovieId", "878");
        assertThat(items.get(1))
                .containsEntry("type", "DISCOVERY")
                .containsEntry("flavor_code", "FLAVOR_A");
        assertThat(items.get(1).get("reason").toString()).isEqualTo("{\"reasons\": []}");
    }

    @Test
    @DisplayName("T9 영화 저장 하나가 실패하면 세트와 다른 영화도 모두 되돌린다")
    void rollsBackSetWhenAnyItemInsertFails() {
        movies(100, 101);
        candidates(candidate(100, "5.0", "TASTE", null), candidate(101, "4.0", "TASTE", null));
        doAnswer(
                        invocation -> {
                            long setId = invocation.getArgument(0);
                            jdbc.update(
                                    "INSERT INTO recommendation_items"
                                            + " (set_id,movie_id,type,position,reason)"
                                            + " VALUES (?,100,'TASTE',1,'{}'::jsonb)",
                                    setId);
                            jdbc.update(
                                    "INSERT INTO recommendation_items"
                                            + " (set_id,movie_id,type,position,reason)"
                                            + " VALUES (?,100,'TASTE',2,'{}'::jsonb)",
                                    setId);
                            return null;
                        })
                .when(bufferRepository)
                .insertItems(anyLong(), anyList());

        assertThatThrownBy(() -> service.createBuffer(USER))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(rowCount("recommendation_sets")).isZero();
        assertThat(rowCount("recommendation_items")).isZero();
    }

    @Test
    @DisplayName("T10 같은 요청을 반복해도 세트는 하나만 만든다")
    void reusesOneSetForDuplicateRequests() {
        movies(100, 101);
        candidates(candidate(100, "5.0", "TASTE", null), candidate(101, "4.0", "TASTE", null));

        long first = service.createBuffer(USER).recommendationSetId();
        long second = service.createBuffer(USER).recommendationSetId();

        assertThat(second).isEqualTo(first);
        assertThat(rowCount("recommendation_sets")).isEqualTo(1);
        assertThat(rowCount("recommendation_items")).isEqualTo(2);
    }

    @Test
    @DisplayName("T10 같은 요청이 동시에 와도 세트와 영화가 중복되지 않는다")
    void reusesOneSetForConcurrentRequests() throws Exception {
        movies(100, 101);
        candidates(candidate(100, "5.0", "TASTE", null), candidate(101, "4.0", "TASTE", null));
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Long> first = executor.submit(() -> createTogether(ready, start));
        Future<Long> second = executor.submit(() -> createTogether(ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        assertThat(rowCount("recommendation_sets")).isEqualTo(1);
        assertThat(rowCount("recommendation_items")).isEqualTo(2);
    }

    @Test
    @DisplayName("T11 Redis generation ID가 바뀌면 새 버퍼를 만든다")
    void createsNewSetForChangedGenerationId() {
        movies(100, 101);
        candidates(candidate(100, "5.0", "TASTE", null));
        long first = service.createBuffer(USER).recommendationSetId();

        candidates("generation-4", 3L, candidate(101, "5.0", "TASTE", null));
        long second = service.createBuffer(USER).recommendationSetId();

        assertThat(second).isNotEqualTo(first);
        assertThat(storedMovieIds(first)).containsExactly(100L);
        assertThat(storedMovieIds(second)).containsExactly(101L);
    }

    @Test
    @DisplayName("T12 Redis가 실패하면 빈 세트를 남기지 않는다")
    void failsWithoutPersistingWhenRedisIsUnavailable() {
        when(candidateReader.read(USER))
                .thenThrow(new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE));

        assertUnavailable(() -> service.createBuffer(USER));
        assertThat(rowCount("recommendation_sets")).isZero();
    }

    @Test
    @DisplayName("T12 필터 뒤에 영화가 하나도 없으면 빈 세트를 남기지 않는다")
    void failsWithoutPersistingWhenNoCandidatesRemain() {
        candidates(candidate(9999, "5.0", "TASTE", null));

        assertUnavailable(() -> service.createBuffer(USER));
        assertThat(rowCount("recommendation_sets")).isZero();
    }

    private long createTogether(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return service.createBuffer(USER).recommendationSetId();
    }

    private void candidates(RecommendationCandidate... items) {
        candidates(3L, items);
    }

    private void candidates(long candidateVersion, RecommendationCandidate... items) {
        candidates("generation-" + candidateVersion, candidateVersion, items);
    }

    private void candidates(
            String generationId, long candidateVersion, RecommendationCandidate... items) {
        when(candidateReader.read(USER))
                .thenReturn(
                        new RecommendationCandidateBatch(
                                "als-serving-example",
                                candidateVersion,
                                generationId,
                                UPDATED_AT,
                                List.of(items)));
    }

    private RecommendationCandidate candidate(
            long movieId, String score, String type, String flavorCode) {
        return candidate(movieId, score, type, flavorCode, "{\"reasons\":[]}");
    }

    private RecommendationCandidate candidate(
            long movieId, String score, String type, String flavorCode, String reason) {
        try {
            return new RecommendationCandidate(
                    movieId,
                    new BigDecimal(score),
                    type,
                    flavorCode,
                    new tools.jackson.databind.ObjectMapper().readTree(reason));
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private void user(long id) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                id,
                "recommendation-" + id + "@example.test",
                "recommendation" + id);
    }

    private void movies(long... ids) {
        for (long id : ids) {
            movie(id);
        }
    }

    private void movie(long id) {
        jdbc.update(
                "INSERT INTO movies(id,tmdb_id,title) VALUES (?,?,?)",
                id,
                Math.toIntExact(10000 + id),
                "movie-" + id);
    }

    private void ott(long id, String code) {
        jdbc.update(
                "INSERT INTO otts(id,code,name,tmdb_provider_id) VALUES (?,?,?,?)",
                id,
                code,
                code,
                Math.toIntExact(100 + id));
    }

    private void movieOtt(long movieId, long ottId) {
        jdbc.update(
                "INSERT INTO movie_ott_providers(movie_id,ott_id) VALUES (?,?)", movieId, ottId);
    }

    private void rating(long userId, long movieId) {
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (?,?,5)", userId, movieId);
    }

    private void watchRecord(long userId, long movieId, Long ottId, String status) {
        jdbc.update(
                """
                INSERT INTO watch_records(user_id,movie_id,ott_id,status,clicked_at)
                VALUES (?,?,?,?,TIMESTAMPTZ '2026-09-13T01:00:00Z')
                """,
                userId,
                movieId,
                ottId,
                status);
    }

    private void dismissal(long userId, long movieId) {
        jdbc.update(
                "INSERT INTO user_movie_dismissals(user_id,movie_id,created_at)"
                        + " VALUES (?,?,TIMESTAMPTZ '2026-09-13T01:00:00Z')",
                userId,
                movieId);
    }

    private List<Long> storedMovieIds(long setId) {
        return jdbc.queryForList(
                "SELECT movie_id FROM recommendation_items WHERE set_id=? ORDER BY position",
                Long.class,
                setId);
    }

    private List<Long> storedMovieIds(long setId, String type) {
        return jdbc.queryForList(
                "SELECT movie_id FROM recommendation_items"
                        + " WHERE set_id=? AND type=? ORDER BY position",
                Long.class,
                setId,
                type);
    }

    private List<Integer> storedPositions(long setId) {
        return jdbc.queryForList(
                "SELECT position FROM recommendation_items WHERE set_id=? ORDER BY position",
                Integer.class,
                setId);
    }

    private List<Integer> storedPositions(long setId, String type) {
        return jdbc.queryForList(
                "SELECT position FROM recommendation_items"
                        + " WHERE set_id=? AND type=? ORDER BY position",
                Integer.class,
                setId,
                type);
    }

    private long rowCount(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.RECOMMENDATION_UNAVAILABLE);
    }
}
