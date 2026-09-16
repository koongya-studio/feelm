package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.recommendation.repository.RecommendationDismissRepository;
import com.feelm.backend.domain.recommendation.service.RecommendationDismissService;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import com.feelm.backend.infrastructure.kafka.KafkaEventType;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** API-047: 실제 마이그레이션과 PostgreSQL을 사용하는 관심없음 HTTP 계약 테스트. */
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.task.scheduling.enabled=false"
        })
@AutoConfigureMockMvc
@DisplayName("API-047 관심없음 처리")
class RecommendationDismissApiContractTest extends SharedPostgresContractTest {

    private static final long USER = 10;
    private static final long OTHER_USER = 20;
    private static final long TASTE_MOVIE = 100;
    private static final long NEXT_TASTE_MOVIE = 101;
    private static final long DISCOVERY_MOVIE = 102;
    private static final long OTHER_MOVIE = 103;
    private static final long SET = 1000;
    private static final long OTHER_SET = 2000;
    private static final long TASTE_ITEM = 3000;
    private static final long NEXT_TASTE_ITEM = 3001;
    private static final long DISCOVERY_ITEM = 3002;
    private static final long OTHER_USER_ITEM = 4000;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired RecommendationDismissService recommendationDismissService;
    @MockitoSpyBean RecommendationDismissRepository recommendationDismissRepository;
    @MockitoSpyBean KafkaEventPublisher kafkaEventPublisherSpy;

    private ExecutorService executor;

    @BeforeEach
    void fixtures() {
        jdbc.execute(
                "TRUNCATE TABLE outbox_events, user_movie_dismissals, recommendation_items,"
                        + " recommendation_sets, movies, users, popcorn_flavors, genres, otts"
                        + " RESTART IDENTITY CASCADE");
        user(USER, "user");
        user(OTHER_USER, "other");
        movie(TASTE_MOVIE, "현재 취향 추천");
        movie(NEXT_TASTE_MOVIE, "다음 취향 추천");
        movie(DISCOVERY_MOVIE, "다음 발견 추천");
        movie(OTHER_MOVIE, "다른 영화");
        recommendationSet(SET, USER);
        recommendationSet(OTHER_SET, OTHER_USER);
        recommendationItem(TASTE_ITEM, SET, TASTE_MOVIE, "TASTE", 1, true);
        recommendationItem(NEXT_TASTE_ITEM, SET, NEXT_TASTE_MOVIE, "TASTE", 2, false);
        recommendationItem(DISCOVERY_ITEM, SET, DISCOVERY_MOVIE, "DISCOVERY", 3, false);
        recommendationItem(OTHER_USER_ITEM, OTHER_SET, OTHER_MOVIE, "TASTE", 1, true);
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
    @DisplayName("T1 현재 취향 추천을 넘기면 다음 취향 추천으로 교체한다")
    void dismissesMovieAndReturnsSameTypeReplacement() throws Exception {
        dismiss(USER, TASTE_MOVIE, itemBody(TASTE_ITEM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movieId").value(TASTE_MOVIE))
                .andExpect(jsonPath("$.replacement.recommendationItemId").value(NEXT_TASTE_ITEM))
                .andExpect(jsonPath("$.replacement.type").value("TASTE"));

        assertThat(dismissalRows(USER, TASTE_MOVIE)).isEqualTo(1);
        assertThat(exposedAt(NEXT_TASTE_ITEM)).isNotNull();
        assertThat(exposedAt(DISCOVERY_ITEM)).isNull();
        assertThat(setInputVersion(SET)).isEqualTo("input-v1");
    }

    @Test
    @DisplayName("T2 같은 종류의 다음 영화가 없으면 교체 없이 관심없음을 저장한다")
    void returnsNullWhenSameTypeBufferIsExhausted() throws Exception {
        jdbc.update("DELETE FROM recommendation_items WHERE id = ?", NEXT_TASTE_ITEM);

        dismiss(USER, TASTE_MOVIE, itemBody(TASTE_ITEM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movieId").value(TASTE_MOVIE))
                .andExpect(jsonPath("$.replacement").value(nullValue()));

        assertThat(dismissalRows(USER, TASTE_MOVIE)).isEqualTo(1);
        assertThat(exposedAt(DISCOVERY_ITEM)).isNull();
        assertThat(dismissEventRows(USER, TASTE_MOVIE)).isEqualTo(1);
    }

    @Test
    @DisplayName("T3 추천 카드가 아닌 영화도 관심없음 처리할 수 있다")
    void dismissesWithoutRecommendationSource() throws Exception {
        dismiss(USER, OTHER_MOVIE, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movieId").value(OTHER_MOVIE))
                .andExpect(jsonPath("$.replacement").value(nullValue()));

        assertThat(dismissalSource(USER, OTHER_MOVIE)).isNull();
        assertThat(dismissEventRows(USER, OTHER_MOVIE)).isEqualTo(1);
    }

    @Test
    @DisplayName("T4 존재하지 않는 영화는 관심없음 처리하지 않는다")
    void rejectsUnknownMovie() throws Exception {
        dismiss(USER, 999999, "{}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertNothingWasWritten();
    }

    @Test
    @DisplayName("T5 다른 사용자의 추천 카드 ID는 사용할 수 없다")
    void rejectsForeignRecommendationItem() throws Exception {
        dismiss(USER, OTHER_MOVIE, itemBody(OTHER_USER_ITEM))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertNothingWasWritten();
    }

    @Test
    @DisplayName("T6 카드 ID와 URL의 영화가 다르면 요청을 거절한다")
    void rejectsMovieMismatch() throws Exception {
        dismiss(USER, OTHER_MOVIE, itemBody(TASTE_ITEM))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertNothingWasWritten();
    }

    @Test
    @DisplayName("미노출 추천 카드 ID는 관심없음 요청의 출처로 사용할 수 없다")
    void rejectsUnexposedRecommendationItem() throws Exception {
        dismiss(USER, NEXT_TASTE_MOVIE, itemBody(NEXT_TASTE_ITEM))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertNothingWasWritten();
    }

    @Test
    @DisplayName("T7 이미 관심없음 처리한 영화는 다시 처리하지 않는다")
    void rejectsAlreadyDismissedMovie() throws Exception {
        jdbc.update(
                "INSERT INTO user_movie_dismissals(user_id,movie_id) VALUES (?,?)",
                USER,
                OTHER_MOVIE);

        dismiss(USER, OTHER_MOVIE, "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_DISMISSED"));

        assertThat(dismissalRows(USER, OTHER_MOVIE)).isEqualTo(1);
        assertThat(outboxRows()).isZero();
    }

    @Test
    @DisplayName("T8 로그인하지 않으면 관심없음 처리할 수 없다")
    void requiresAuthentication() throws Exception {
        mvc.perform(post(path(OTHER_MOVIE)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertNothingWasWritten();
    }

    @Test
    @DisplayName("비활성 계정은 관심없음 처리할 수 없다")
    void rejectsInactiveUser() throws Exception {
        jdbc.update("UPDATE users SET status='WITHDRAWN' WHERE id=?", USER);

        dismiss(USER, OTHER_MOVIE, "{}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertNothingWasWritten();
    }

    @Test
    @DisplayName("과거 추천 카드의 관심없음은 저장하지만 교체 카드를 꺼내지 않는다")
    void doesNotReplaceFromPastRecommendationSet() throws Exception {
        long currentSet = 1001;
        recommendationSet(currentSet, USER, OffsetDateTime.now().plusDays(1));

        dismiss(USER, TASTE_MOVIE, itemBody(TASTE_ITEM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movieId").value(TASTE_MOVIE))
                .andExpect(jsonPath("$.replacement").value(nullValue()));

        assertThat(dismissalSource(USER, TASTE_MOVIE)).isEqualTo(TASTE_ITEM);
        assertThat(dismissEventRows(USER, TASTE_MOVIE)).isEqualTo(1);
        assertThat(exposedAt(NEXT_TASTE_ITEM)).isNull();
    }

    @Test
    @DisplayName("T12 관심없음 이벤트를 계약된 봉투와 내용으로 기록한다")
    void recordsDismissEnvelope() throws Exception {
        dismiss(USER, TASTE_MOVIE, itemBody(TASTE_ITEM)).andExpect(status().isOk());

        List<Map<String, Object>> events =
                jdbc.queryForList(
                        "SELECT event_type,topic,partition_key,status,"
                                + " message->>'eventId' AS event_id,"
                                + " message->>'occurredAt' AS occurred_at,"
                                + " message->>'eventType' AS message_event_type,"
                                + " (message->>'userId')::bigint AS message_user_id,"
                                + " (message->'payload'->>'movieId')::bigint AS movie_id,"
                                + " (message->'payload'->>'recommendationItemId')::bigint"
                                + " AS recommendation_item_id"
                                + " FROM outbox_events");
        assertThat(events).hasSize(1);
        assertThat(events.getFirst())
                .containsEntry("event_type", "DISMISS")
                .containsEntry("topic", "feelm.dismiss.v1")
                .containsEntry("partition_key", USER)
                .containsEntry("status", "PENDING")
                .containsEntry("message_event_type", "DISMISS")
                .containsEntry("message_user_id", USER)
                .containsEntry("movie_id", TASTE_MOVIE)
                .containsEntry("recommendation_item_id", TASTE_ITEM);
        assertThatCode(() -> UUID.fromString((String) events.getFirst().get("event_id")))
                .doesNotThrowAnyException();
        assertThat(OffsetDateTime.parse((String) events.getFirst().get("occurred_at")).getOffset())
                .isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @Transactional
    @DisplayName("T9 교체할 때 같은 종류에서 아직 보여주지 않은 첫 영화를 선택한다")
    void selectsFirstUnexposedItemOfSameType() {
        assertThat(recommendationDismissRepository.lockNextReplacement(USER, SET, "TASTE"))
                .get()
                .extracting(
                        RecommendationDismissRepository.ReplacementItem::id,
                        RecommendationDismissRepository.ReplacementItem::movieId,
                        RecommendationDismissRepository.ReplacementItem::type)
                .containsExactly(NEXT_TASTE_ITEM, NEXT_TASTE_MOVIE, "TASTE");
    }

    @Test
    @DisplayName("T10 같은 영화를 동시에 요청해도 관심없음과 이벤트는 하나만 저장한다")
    void mapsConcurrentDuplicateToAlreadyDismissed() throws Exception {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        String token = bearer(USER);
        Future<Integer> first =
                executor.submit(() -> concurrentDismiss(OTHER_MOVIE, "{}", token, ready, start));
        Future<Integer> second =
                executor.submit(() -> concurrentDismiss(OTHER_MOVIE, "{}", token, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200, 409);
        assertThat(dismissalRows(USER, OTHER_MOVIE)).isEqualTo(1);
        assertThat(dismissEventRows(USER, OTHER_MOVIE)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 추천 묶음의 서로 다른 두 카드를 동시에 넘겨도 후보를 중복 사용하지 않는다")
    void doesNotConsumeOneReplacementTwice() throws Exception {
        long secondReplacementMovie = 104;
        long secondReplacementItem = 3003;
        movie(secondReplacementMovie, "두 번째 다음 취향 추천");
        jdbc.update("UPDATE recommendation_items SET exposed_at=now() WHERE id=?", NEXT_TASTE_ITEM);
        recommendationItem(3004, SET, OTHER_MOVIE, "TASTE", 4, false);
        recommendationItem(secondReplacementItem, SET, secondReplacementMovie, "TASTE", 5, false);

        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        String token = bearer(USER);
        Future<Integer> first =
                executor.submit(
                        () ->
                                concurrentDismiss(
                                        TASTE_MOVIE, itemBody(TASTE_ITEM), token, ready, start));
        Future<Integer> second =
                executor.submit(
                        () ->
                                concurrentDismiss(
                                        NEXT_TASTE_MOVIE,
                                        itemBody(NEXT_TASTE_ITEM),
                                        token,
                                        ready,
                                        start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(exposedAt(3004)).isNotNull();
        assertThat(exposedAt(secondReplacementItem)).isNotNull();
        assertThat(dismissalRows(USER, TASTE_MOVIE)).isEqualTo(1);
        assertThat(dismissalRows(USER, NEXT_TASTE_MOVIE)).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 관심없음 처리한 버퍼 영화는 교체 카드로 다시 보여주지 않는다")
    void skipsDismissedMovieWhenSelectingReplacement() throws Exception {
        jdbc.update(
                "INSERT INTO user_movie_dismissals(user_id,movie_id) VALUES (?,?)",
                USER,
                NEXT_TASTE_MOVIE);

        dismiss(USER, TASTE_MOVIE, itemBody(TASTE_ITEM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replacement").value(nullValue()));

        assertThat(exposedAt(NEXT_TASTE_ITEM)).isNull();
        assertThat(exposedAt(DISCOVERY_ITEM)).isNull();
    }

    @Test
    @DisplayName("T11 이벤트 기록에 실패하면 관심없음과 카드 교체를 모두 되돌린다")
    void rollsBackWhenOutboxWriteFails() throws Exception {
        doThrow(new IllegalStateException("forced outbox failure"))
                .when(kafkaEventPublisherSpy)
                .publish(eq(KafkaEventType.DISMISS), anyLong(), any());

        dismiss(USER, TASTE_MOVIE, itemBody(TASTE_ITEM))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(dismissalRows(USER, TASTE_MOVIE)).isZero();
        assertThat(exposedAt(NEXT_TASTE_ITEM)).isNull();
        assertThat(outboxRows()).isZero();
    }

    @Test
    @DisplayName("추천 카드가 검증 직후 삭제되면 404로 응답하고 모든 변경을 되돌린다")
    void mapsDeletedRecommendationItemToNotFound() throws Exception {
        doAnswer(
                        invocation -> {
                            jdbc.update("DELETE FROM recommendation_items WHERE id=?", TASTE_ITEM);
                            return invocation.callRealMethod();
                        })
                .when(recommendationDismissRepository)
                .saveDismissal(USER, TASTE_MOVIE, TASTE_ITEM);

        dismiss(USER, TASTE_MOVIE, itemBody(TASTE_ITEM))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(dismissalRows(USER, TASTE_MOVIE)).isZero();
        assertThat(recommendationItemRows(TASTE_ITEM)).isEqualTo(1);
        assertThat(exposedAt(NEXT_TASTE_ITEM)).isNull();
        assertThat(outboxRows()).isZero();
    }

    @Test
    @DisplayName("T13 다음 추천에서 제외할 때 현재 사용자의 관심없음 영화만 찾는다")
    void findsDismissedMovieIdsForNextBuffer() {
        jdbc.update(
                "INSERT INTO user_movie_dismissals(user_id,movie_id) VALUES (?,?),(?,?)",
                USER,
                TASTE_MOVIE,
                OTHER_USER,
                OTHER_MOVIE);

        assertThat(recommendationDismissService.dismissedMovieIds(USER))
                .containsExactly(TASTE_MOVIE);
    }

    @Test
    @DisplayName("T14 교체 카드에 영화·장르·OTT·맛·추천 이유를 모두 담는다")
    void mapsReplacementContract() throws Exception {
        jdbc.update(
                "INSERT INTO popcorn_flavors"
                        + "(id,code,display_name,color,sort_order,cluster_id,representative_keywords)"
                        + " VALUES (1,'FLAVOR_B','다크초코','#3C2418',1,1,'[]'::jsonb)");
        jdbc.update(
                "UPDATE movies SET poster_url='https://image.test/poster.jpg',"
                        + " release_date=DATE '1999-10-15', average_rating=4.30,"
                        + " representative_flavor_id=1 WHERE id=?",
                NEXT_TASTE_MOVIE);
        jdbc.update(
                "INSERT INTO genres(id,code,name,tmdb_genre_id)"
                        + " VALUES (1,'DRAMA','드라마',18),(2,'THRILLER','스릴러',53)");
        jdbc.update(
                "INSERT INTO movie_genres(movie_id,genre_id) VALUES (?,1),(?,2)",
                NEXT_TASTE_MOVIE,
                NEXT_TASTE_MOVIE);
        jdbc.update(
                "INSERT INTO otts(id,code,name,tmdb_provider_id)"
                        + " VALUES (1,'NETFLIX','넷플릭스',8)");
        jdbc.update(
                "INSERT INTO movie_ott_providers(movie_id,ott_id) VALUES (?,1)", NEXT_TASTE_MOVIE);
        jdbc.update(
                "UPDATE recommendation_items SET reason="
                        + "'{\"summary\":\"취향과 잘 맞아요\",\"factors\":[]}'::jsonb WHERE id=?",
                NEXT_TASTE_ITEM);

        dismiss(USER, TASTE_MOVIE, itemBody(TASTE_ITEM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replacement.movie.movieId").value(NEXT_TASTE_MOVIE))
                .andExpect(jsonPath("$.replacement.movie.title").value("다음 취향 추천"))
                .andExpect(
                        jsonPath("$.replacement.movie.posterUrl")
                                .value("https://image.test/poster.jpg"))
                .andExpect(jsonPath("$.replacement.movie.releaseYear").value(1999))
                .andExpect(jsonPath("$.replacement.movie.genres[0]").value("드라마"))
                .andExpect(jsonPath("$.replacement.movie.genres[1]").value("스릴러"))
                .andExpect(jsonPath("$.replacement.movie.averageRating").value(4.3))
                .andExpect(jsonPath("$.replacement.movie.ottProviders[0]").value("NETFLIX"))
                .andExpect(jsonPath("$.replacement.flavorCode").value("FLAVOR_B"))
                .andExpect(jsonPath("$.replacement.reason.summary").value("취향과 잘 맞아요"));
    }

    private org.springframework.test.web.servlet.ResultActions dismiss(
            long userId, long movieId, String body) throws Exception {
        return mvc.perform(
                post(path(movieId))
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private String path(long movieId) {
        return "/api/v1/movies/" + movieId + "/dismiss";
    }

    private String itemBody(long recommendationItemId) {
        return "{\"recommendationItemId\":" + recommendationItemId + "}";
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    private int concurrentDismiss(
            long movieId, String body, String token, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return mvc.perform(
                        post(path(movieId))
                                .header("Authorization", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private void user(long id, String nickname) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                id,
                nickname + "@example.test",
                nickname);
    }

    private void movie(long id, String title) {
        jdbc.update(
                "INSERT INTO movies(id,tmdb_id,title) VALUES (?,?,?)",
                id,
                Math.toIntExact(id + 10000),
                title);
    }

    private void recommendationSet(long id, long userId) {
        recommendationSet(id, userId, "input-v1", OffsetDateTime.now());
    }

    private void recommendationSet(long id, long userId, OffsetDateTime generatedAt) {
        recommendationSet(id, userId, "input-v2", generatedAt);
    }

    private void recommendationSet(
            long id, long userId, String inputVersion, OffsetDateTime generatedAt) {
        jdbc.update(
                "INSERT INTO recommendation_sets"
                        + "(id,user_id,input_version,model_version,generated_at)"
                        + " VALUES (?,?,?,'model-v1',?)",
                id,
                userId,
                inputVersion,
                generatedAt);
    }

    private void recommendationItem(
            long id, long setId, long movieId, String type, int position, boolean exposed) {
        jdbc.update(
                "INSERT INTO recommendation_items"
                        + "(id,set_id,movie_id,type,position,reason,exposed_at)"
                        + " VALUES (?,?,?,?,?,'{}'::jsonb,?)",
                id,
                setId,
                movieId,
                type,
                position,
                exposed ? OffsetDateTime.now() : null);
    }

    private int dismissalRows(long userId, long movieId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM user_movie_dismissals WHERE user_id=? AND movie_id=?",
                Integer.class,
                userId,
                movieId);
    }

    private int recommendationItemRows(long recommendationItemId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM recommendation_items WHERE id=?",
                Integer.class,
                recommendationItemId);
    }

    private Long dismissalSource(long userId, long movieId) {
        return jdbc.queryForObject(
                "SELECT recommendation_item_id FROM user_movie_dismissals"
                        + " WHERE user_id=? AND movie_id=?",
                Long.class,
                userId,
                movieId);
    }

    private OffsetDateTime exposedAt(long itemId) {
        return jdbc.queryForObject(
                "SELECT exposed_at FROM recommendation_items WHERE id=?",
                (resultSet, rowNumber) -> resultSet.getObject(1, OffsetDateTime.class),
                itemId);
    }

    private String setInputVersion(long setId) {
        return jdbc.queryForObject(
                "SELECT input_version FROM recommendation_sets WHERE id=?", String.class, setId);
    }

    private int dismissEventRows(long userId, long movieId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type='DISMISS'"
                        + " AND partition_key=? AND (message->'payload'->>'movieId')::bigint=?",
                Integer.class,
                userId,
                movieId);
    }

    private int outboxRows() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);
    }

    private void assertNothingWasWritten() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_movie_dismissals", Integer.class))
                .isZero();
        assertThat(outboxRows()).isZero();
        assertThat(exposedAt(NEXT_TASTE_ITEM)).isNull();
    }
}
