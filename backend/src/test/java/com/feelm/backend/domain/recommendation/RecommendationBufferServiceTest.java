package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.recommendation.dto.response.RecommendationResponse;
import com.feelm.backend.domain.recommendation.model.RecommendationBufferResult;
import com.feelm.backend.domain.recommendation.model.RecommendationCandidate;
import com.feelm.backend.domain.recommendation.model.RecommendationCandidateBatch;
import com.feelm.backend.domain.recommendation.repository.RecommendationBufferRepository;
import com.feelm.backend.domain.recommendation.repository.RecommendationCandidateReader;
import com.feelm.backend.domain.recommendation.repository.RecommendationReasonGenerator;
import com.feelm.backend.domain.recommendation.repository.RecommendationReasonRepository;
import com.feelm.backend.domain.recommendation.service.RecommendationBufferService;
import com.feelm.backend.domain.recommendation.service.RecommendationServingService;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    @Autowired RecommendationServingService servingService;
    @MockitoSpyBean RecommendationReasonRepository reasonRepository;
    @MockitoBean RecommendationCandidateReader candidateReader;
    @MockitoBean RecommendationReasonGenerator reasonGenerator;
    @MockitoSpyBean RecommendationBufferRepository bufferRepository;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        reset(candidateReader, reasonGenerator, reasonRepository);
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
    @DisplayName("TASTE에 저장한 영화는 DISCOVERY에서 건너뛰고 다음 고유 후보를 채운다")
    void skipsDiscoveryMoviesAlreadyStoredAsTaste() {
        movies(100, 101, 102, 200, 201);
        candidates(
                candidate(100, "5.0", "TASTE", null),
                candidate(101, "4.0", "TASTE", null),
                candidate(102, "3.0", "TASTE", null),
                candidate(102, "6.0", "DISCOVERY", "FLAVOR_A"),
                candidate(200, "5.0", "DISCOVERY", "FLAVOR_B"),
                candidate(100, "4.0", "DISCOVERY", "FLAVOR_C"),
                candidate(201, "3.0", "DISCOVERY", "FLAVOR_D"));

        long setId = service.createBuffer(USER).recommendationSetId();

        assertThat(storedMovieIds(setId, "TASTE")).containsExactly(100L, 101L, 102L);
        assertThat(storedMovieIds(setId, "DISCOVERY")).containsExactly(200L, 201L);
        assertThat(servingService.getRecommendations(USER).items())
                .extracting(item -> item.movie().movieId())
                .containsExactly(100L, 101L, 200L);
    }

    @Test
    @DisplayName("DISCOVERY가 모두 TASTE와 겹치면 세 번째 TASTE를 노출한다")
    void fallsBackToTasteWhenAllDiscoveryMoviesOverlap() {
        movies(100, 101, 102);
        candidates(
                candidate(100, "5.0", "TASTE", null),
                candidate(101, "4.0", "TASTE", null),
                candidate(102, "3.0", "TASTE", null),
                candidate(100, "6.0", "DISCOVERY", "FLAVOR_A"),
                candidate(101, "5.0", "DISCOVERY", "FLAVOR_B"));

        long setId = service.createBuffer(USER).recommendationSetId();

        assertThat(storedMovieIds(setId, "DISCOVERY")).isEmpty();
        assertThat(servingService.getRecommendations(USER).items())
                .extracting(item -> item.movie().movieId())
                .containsExactly(100L, 101L, 102L);
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
                        {"reasons":[{"code":"SHARED_GENRE",\
                        "sourceMovieIds":[55],"matchedGenreCodes":["THRILLER"]}]}
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
                .contains("SHARED_GENRE", "sourceMovieIds", "THRILLER", "새로운 영화를 만나보세요.");
        assertThat(items.get(1))
                .containsEntry("type", "DISCOVERY")
                .containsEntry("flavor_code", "FLAVOR_A");
        assertThat(items.get(1).get("reason").toString())
                .contains("\"reasons\": []", "새로운 영화를 만나보세요.", "\"factors\": []");
    }

    @Test
    @DisplayName("확인된 영화·장르만으로 문구를 만들고 외부 생성은 저장 트랜잭션 밖에서 호출한다")
    void generatesGroundedReasonOutsideBufferTransaction() {
        movies(55, 100);
        jdbc.update(
                "INSERT INTO genres(code,name,tmdb_genre_id) VALUES ('THRILLER','스릴러',53)"
                        + " ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name");
        candidates(
                candidate(
                        100,
                        "5.0",
                        "TASTE",
                        null,
                        """
                        {"reasons":[{"code":"SHARED_GENRE","sourceMovieIds":[55],
                        "matchedGenreCodes":["THRILLER"]}]}
                        """));
        when(reasonGenerator.selectVariants(anyList()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            List<?> prompts = invocation.getArgument(0);
                            assertThat(prompts).hasSize(1);
                            return Map.of(
                                    ((com.feelm.backend.domain.recommendation.model
                                                            .RecommendationReasonPrompt)
                                                    prompts.getFirst())
                                            .itemId(),
                                    "SOURCE_GENRE");
                        });

        long setId = service.createBuffer(USER).recommendationSetId();
        String reason =
                jdbc.queryForObject(
                        "SELECT reason::text FROM recommendation_items WHERE set_id=?",
                        String.class,
                        setId);
        assertThat(reason).contains("SHARED_GENRE", "스릴러", "\"type\": \"GENRE\"");
        assertThat(reason).doesNotContain("movie-55");
    }

    @Test
    @DisplayName("첫 근거의 표시명이 없으면 뒤의 유효한 근거를 사용한다")
    void usesLaterValidReasonWhenFirstCannotBeResolved() {
        movies(55, 100);
        candidates(
                candidate(
                        100,
                        "5.0",
                        "TASTE",
                        null,
                        """
                        {"reasons":[
                          {"code":"TOP_RATED_SOURCE","sourceMovieIds":[999999],"matchedGenreCodes":[]},
                          {"code":"TOP_RATED_SOURCE","sourceMovieIds":[55],"matchedGenreCodes":[]}
                        ]}
                        """));
        when(reasonGenerator.selectVariants(anyList()))
                .thenAnswer(
                        invocation -> {
                            List<
                                            com.feelm.backend.domain.recommendation.model
                                                    .RecommendationReasonPrompt>
                                    prompts = invocation.getArgument(0);
                            assertThat(prompts).hasSize(1);
                            assertThat(prompts.getFirst().sourceTitle()).isEqualTo("movie-55");
                            return Map.of(prompts.getFirst().itemId(), "HISTORY");
                        });

        long setId = service.createBuffer(USER).recommendationSetId();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason->>'summary' FROM recommendation_items WHERE set_id=?",
                                String.class,
                                setId))
                .isEqualTo("영화 'movie-55'을 좋아하신 기록을 참고해 골랐어요.");
    }

    @Test
    @DisplayName("근거 배열의 영화와 장르를 임의로 짝지어 주장하지 않는다")
    void doesNotPairUnrelatedSourceMovieAndGenre() {
        movies(55, 56, 100, 101);
        jdbc.update(
                "INSERT INTO genres(code,name,tmdb_genre_id) VALUES"
                        + " ('ACTION','액션',28),('DRAMA','드라마',18)"
                        + " ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name");
        jdbc.update(
                "INSERT INTO movie_genres(movie_id,genre_id)"
                        + " SELECT 55,id FROM genres WHERE code='DRAMA'");
        jdbc.update(
                "INSERT INTO movie_genres(movie_id,genre_id)"
                        + " SELECT 56,id FROM genres WHERE code='ACTION'");
        candidates(
                candidate(
                        100,
                        "5.0",
                        "TASTE",
                        null,
                        """
                        {"reasons":[{"code":"SHARED_GENRE","sourceMovieIds":[55,56],
                        "matchedGenreCodes":["ACTION","DRAMA"]}]}
                        """),
                candidate(
                        101,
                        "4.0",
                        "DISCOVERY",
                        null,
                        """
                        {"reasons":[{"code":"DISCOVERY_PERSONAL_SCORE","sourceMovieIds":[55,56],
                        "matchedGenreCodes":["ACTION","DRAMA"]}]}
                        """));
        when(reasonGenerator.selectVariants(anyList()))
                .thenAnswer(
                        invocation -> {
                            List<
                                            com.feelm.backend.domain.recommendation.model
                                                    .RecommendationReasonPrompt>
                                    prompts = invocation.getArgument(0);
                            assertThat(prompts).hasSize(2);
                            assertThat(prompts)
                                    .allSatisfy(
                                            prompt -> assertThat(prompt.sourceTitle()).isNull());
                            return Map.of(
                                    prompts.get(0).itemId(),
                                    "SOURCE_GENRE",
                                    prompts.get(1).itemId(),
                                    "SOURCE_GENRE");
                        });

        long setId = service.createBuffer(USER).recommendationSetId();
        assertThat(
                        jdbc.queryForList(
                                "SELECT reason->>'summary' FROM recommendation_items"
                                        + " WHERE set_id=? ORDER BY movie_id",
                                String.class,
                                setId))
                .containsExactly("좋아하신 영화와 액션 장르가 같아요.", "좋아하신 영화와 액션 장르가 같아요.");
    }

    @Test
    @DisplayName("생성기 장애가 추천 버퍼 저장을 실패시키지 않는다")
    void generatorFailureUsesGroundedFallback() {
        movies(55, 100);
        jdbc.update(
                "INSERT INTO genres(code,name,tmdb_genre_id) VALUES ('THRILLER','스릴러',53)"
                        + " ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name");
        candidates(
                candidate(
                        100,
                        "5.0",
                        "TASTE",
                        null,
                        """
                        {"reasons":[{"code":"SHARED_GENRE","sourceMovieIds":[55],
                        "matchedGenreCodes":["THRILLER"]}]}
                        """));
        when(reasonGenerator.selectVariants(anyList()))
                .thenThrow(new IllegalStateException("timeout"));

        long setId = service.createBuffer(USER).recommendationSetId();
        String reason =
                jdbc.queryForObject(
                        "SELECT reason::text FROM recommendation_items WHERE set_id=?",
                        String.class,
                        setId);
        assertThat(reason).contains("좋아하신 영화와 스릴러 장르가 같아요.");
    }

    @Test
    @DisplayName("버퍼 커밋 뒤 이유 조회 DB 오류는 세트 반환을 막지 않고 재요청에서 재시도한다")
    void reasonReadFailureReturnsCommittedSetAndRetries() {
        movies(100);
        candidates(candidate(100, "5.0", "TASTE", null));
        doThrow(new DataAccessResourceFailureException("temporary read failure"))
                .when(reasonRepository)
                .findUnresolved(anyLong());

        long setId = service.createBuffer(USER).recommendationSetId();

        assertThat(rowCount("recommendation_sets")).isEqualTo(1);
        assertThat(rowCount("recommendation_items")).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason->>'summary' FROM recommendation_items WHERE set_id=?",
                                String.class,
                                setId))
                .isNull();
        reset(reasonRepository);
        assertThat(service.createBuffer(USER).recommendationSetId()).isEqualTo(setId);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason->>'summary' FROM recommendation_items WHERE set_id=?",
                                String.class,
                                setId))
                .isEqualTo("새로운 영화를 만나보세요.");
    }

    @Test
    @DisplayName("버퍼 커밋 뒤 이유 갱신 DB 오류는 세트 반환을 막지 않고 재요청에서 재시도한다")
    void reasonUpdateFailureReturnsCommittedSetAndRetries() {
        movies(100, 101);
        candidates(candidate(100, "5.0", "TASTE", null), candidate(101, "4.0", "TASTE", null));
        AtomicInteger updates = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (updates.getAndIncrement() == 0) {
                                return invocation.callRealMethod();
                            }
                            throw new DataAccessResourceFailureException(
                                    "temporary update failure");
                        })
                .when(reasonRepository)
                .completeIfUnexposed(anyLong(), anyString(), anyString());

        long setId = service.createBuffer(USER).recommendationSetId();

        assertThat(rowCount("recommendation_sets")).isEqualTo(1);
        assertThat(rowCount("recommendation_items")).isEqualTo(2);
        assertThat(
                        jdbc.queryForList(
                                "SELECT reason->>'summary' FROM recommendation_items"
                                        + " WHERE set_id=? ORDER BY movie_id",
                                String.class,
                                setId))
                .containsExactly(null, null);
        reset(reasonRepository);
        assertThat(service.createBuffer(USER).recommendationSetId()).isEqualTo(setId);
        assertThat(
                        jdbc.queryForList(
                                "SELECT reason->>'summary' FROM recommendation_items"
                                        + " WHERE set_id=? ORDER BY movie_id",
                                String.class,
                                setId))
                .containsExactly("새로운 영화를 만나보세요.", "새로운 영화를 만나보세요.");
    }

    @Test
    @DisplayName("모델이 허용되지 않은 근거를 선택해도 검증된 장르 문구만 저장한다")
    void rejectsUnsupportedGeneratedVariant() {
        movies(55, 100);
        jdbc.update(
                "INSERT INTO genres(code,name,tmdb_genre_id) VALUES ('THRILLER','스릴러',53)"
                        + " ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name");
        candidates(
                candidate(
                        100,
                        "5.0",
                        "TASTE",
                        null,
                        """
                        {"reasons":[{"code":"SHARED_GENRE","sourceMovieIds":[55],
                        "matchedGenreCodes":["THRILLER"]}]}
                        """));
        when(reasonGenerator.selectVariants(anyList()))
                .thenAnswer(
                        invocation -> {
                            List<
                                            com.feelm.backend.domain.recommendation.model
                                                    .RecommendationReasonPrompt>
                                    prompts = invocation.getArgument(0);
                            return Map.of(prompts.getFirst().itemId(), "SAME_DIRECTOR");
                        });

        long setId = service.createBuffer(USER).recommendationSetId();
        String summary =
                jdbc.queryForObject(
                        "SELECT reason->>'summary' FROM recommendation_items WHERE set_id=?",
                        String.class,
                        setId);
        assertThat(summary).isEqualTo("좋아하신 영화와 스릴러 장르가 같아요.");
        assertThat(summary).doesNotContain("감독", "배우");
    }

    @Test
    @DisplayName("구조화 근거가 없으면 LLM을 호출하지 않고 중립 문구를 저장한다")
    void emptyReasonSkipsGenerator() {
        movies(100);
        candidates(candidate(100, "5.0", "TASTE", null));

        long setId = service.createBuffer(USER).recommendationSetId();

        verifyNoInteractions(reasonGenerator);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason->>'summary' FROM recommendation_items WHERE set_id=?",
                                String.class,
                                setId))
                .isEqualTo("새로운 영화를 만나보세요.");
    }

    @Test
    @DisplayName("확인되지 않은 영화와 장르는 생성 입력에서 제외하고 중립 문구를 저장한다")
    void unknownSourceAndGenreSkipGenerator() {
        movies(100);
        candidates(
                candidate(
                        100,
                        "5.0",
                        "TASTE",
                        null,
                        """
                        {"reasons":[{"code":"SHARED_GENRE","sourceMovieIds":[999999],
                        "matchedGenreCodes":["UNKNOWN_GENRE"]}]}
                        """));

        long setId = service.createBuffer(USER).recommendationSetId();

        verifyNoInteractions(reasonGenerator);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason->>'summary' FROM recommendation_items WHERE set_id=?",
                                String.class,
                                setId))
                .isEqualTo("새로운 영화를 만나보세요.");
    }

    @Test
    @DisplayName("노출된 이유 스냅샷은 뒤늦은 생성 결과로 덮어쓰지 않는다")
    void exposedReasonSnapshotIsImmutable() {
        movies(100);
        candidates(candidate(100, "5.0", "TASTE", null));
        long setId = service.createBuffer(USER).recommendationSetId();
        long itemId =
                jdbc.queryForObject(
                        "SELECT id FROM recommendation_items WHERE set_id=?", Long.class, setId);
        jdbc.update("UPDATE recommendation_items SET exposed_at=now() WHERE id=?", itemId);

        assertThat(reasonRepository.completeIfUnexposed(itemId, "변경된 문구", "[]")).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason->>'summary' FROM recommendation_items WHERE id=?",
                                String.class,
                                itemId))
                .isEqualTo("새로운 영화를 만나보세요.");
    }

    @Test
    @DisplayName("이유 생성 대기 중 첫 노출의 중립 스냅샷은 생성 완료 뒤에도 유지한다")
    void firstExposureWhileGeneratingKeepsNeutralSnapshot() throws Exception {
        movies(55, 100, 101, 200);
        jdbc.update(
                "INSERT INTO genres(code,name,tmdb_genre_id) VALUES ('THRILLER','스릴러',53)"
                        + " ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name");
        candidates(
                candidate(
                        100,
                        "5.0",
                        "TASTE",
                        null,
                        """
                        {"reasons":[{"code":"SHARED_GENRE","sourceMovieIds":[55],
                        "matchedGenreCodes":["THRILLER"]}]}
                        """),
                candidate(101, "4.0", "TASTE", null),
                candidate(200, "3.0", "DISCOVERY", "FLAVOR_A"));
        CountDownLatch generationStarted = new CountDownLatch(1);
        CountDownLatch finishGeneration = new CountDownLatch(1);
        when(reasonGenerator.selectVariants(anyList()))
                .thenAnswer(
                        invocation -> {
                            generationStarted.countDown();
                            if (!finishGeneration.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("generation was not released");
                            }
                            List<
                                            com.feelm.backend.domain.recommendation.model
                                                    .RecommendationReasonPrompt>
                                    prompts = invocation.getArgument(0);
                            return Map.of(prompts.getFirst().itemId(), "SOURCE_GENRE");
                        });
        executor = Executors.newSingleThreadExecutor();
        Future<Long> creation =
                executor.submit(() -> service.createBuffer(USER).recommendationSetId());

        try {
            assertThat(generationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            RecommendationResponse first = servingService.getRecommendations(USER);
            assertThat(first.items()).hasSize(3);
            long itemId =
                    first.items().stream()
                            .filter(item -> item.movie().movieId() == 100L)
                            .findFirst()
                            .orElseThrow()
                            .recommendationItemId();
            assertThat(
                            first.items().stream()
                                    .filter(item -> item.recommendationItemId() == itemId)
                                    .findFirst()
                                    .orElseThrow()
                                    .reason()
                                    .path("summary")
                                    .textValue())
                    .isEqualTo("새로운 영화를 만나보세요.");
            assertThat(
                            first.items().stream()
                                    .filter(item -> item.recommendationItemId() == itemId)
                                    .findFirst()
                                    .orElseThrow()
                                    .reason()
                                    .path("factors")
                                    .isEmpty())
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT exposed_at IS NOT NULL FROM recommendation_items WHERE id=?",
                                    Boolean.class,
                                    itemId))
                    .isTrue();
            finishGeneration.countDown();
            assertThat(creation.get(5, TimeUnit.SECONDS)).isEqualTo(first.recommendationSetId());
            RecommendationResponse later = servingService.getRecommendations(USER);
            assertThat(
                            later.items().stream()
                                    .filter(item -> item.recommendationItemId() == itemId)
                                    .findFirst()
                                    .orElseThrow()
                                    .reason())
                    .isEqualTo(
                            first.items().stream()
                                    .filter(item -> item.recommendationItemId() == itemId)
                                    .findFirst()
                                    .orElseThrow()
                                    .reason());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT reason->>'summary' FROM recommendation_items WHERE id=?",
                                    String.class,
                                    itemId))
                    .isNull();
        } finally {
            finishGeneration.countDown();
        }
    }

    @Test
    @DisplayName("v6의 입력 영화·그룹·개인 점수 근거를 과장 없이 문구로 변환한다")
    void rendersRemainingV6ReasonCodesWithoutUnsupportedClaims() {
        movies(55, 100, 101, 102);
        candidates(
                candidate(
                        100,
                        "5.0",
                        "TASTE",
                        null,
                        """
                        {"reasons":[{"code":"TOP_RATED_SOURCE","sourceMovieIds":[55],
                        "matchedGenreCodes":[]}]}
                        """),
                candidate(
                        101,
                        "4.0",
                        "DISCOVERY",
                        null,
                        """
                        {"reasons":[{"code":"DISCOVERY_AGGREGATE_AFFINITY",
                        "directionSource":"RATING","topContributors":[{"movieId":55,
                        "relation":"POSITIVE_ALIGNMENT"}],"topContributionCoverage":0.5}]}
                        """),
                candidate(
                        102,
                        "3.0",
                        "DISCOVERY",
                        null,
                        """
                        {"reasons":[{"code":"DISCOVERY_PERSONAL_SCORE",
                        "sourceMovieIds":[55],"matchedGenreCodes":[]}]}
                        """));

        long setId = service.createBuffer(USER).recommendationSetId();
        List<String> summaries =
                jdbc.queryForList(
                        "SELECT reason->>'summary' FROM recommendation_items WHERE set_id=?"
                                + " ORDER BY movie_id",
                        String.class,
                        setId);
        assertThat(summaries)
                .containsExactly(
                        "좋아하신 영화 'movie-55'을 바탕으로 골랐어요.",
                        "평가 이력을 바탕으로 발견한 영화예요.",
                        "취향을 바탕으로 새롭게 고른 영화예요.");
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
