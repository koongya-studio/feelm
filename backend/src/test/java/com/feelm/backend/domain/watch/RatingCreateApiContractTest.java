package com.feelm.backend.domain.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@DisplayName("평가 생성 API 계약 — API-036")
class RatingCreateApiContractTest extends SharedPostgresContractTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private JdbcClient jdbc;

    private long userId;
    private long withdrawnUserId;
    private long movieId;
    private long otherMovieId;
    private long flavorId;

    @BeforeEach
    void setUp() {
        truncate();
        flavorId = insertFlavor("FLAVOR_TEST");
        userId = insertUser("rating@feelm.test", "평가자", "ACTIVE");
        withdrawnUserId = insertUser("withdrawn-rating@feelm.test", "탈퇴평가자", "WITHDRAWN");
        movieId = insertMovie(880_001, "평가 테스트 영화", flavorId);
        otherMovieId = insertMovie(880_002, "다른 평가 영화", flavorId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_rating_outbox ON outbox_events").update();
        jdbc.sql("DROP FUNCTION IF EXISTS test_fail_rating_outbox()").update();
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_rating_frame ON frames").update();
        jdbc.sql("DROP FUNCTION IF EXISTS test_fail_rating_frame()").update();
        truncate();
    }

    @Test
    @DisplayName("평점·프레임·영화 집계와 PENDING 이벤트를 한 번에 저장한다")
    void createRatingPersistsDomainAndPendingEvent() throws Exception {
        Instant before = Instant.now();
        JsonNode response =
                create(
                        movieId,
                        userId,
                        "{\"score\":4.5,\"comment\":\"좋았어요\",\"visibility\":\"PUBLIC\"}",
                        201);
        Instant after = Instant.now();

        long ratingId = response.path("ratingId").asLong();
        long frameId = response.path("frame").path("frameId").asLong();
        Instant createdAt = Instant.parse(response.path("createdAt").asText());
        assertThat(response.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "ratingId",
                        "movieId",
                        "score",
                        "comment",
                        "visibility",
                        "spoiler",
                        "frame",
                        "popcorn",
                        "watchStatus",
                        "createdAt");
        assertThat(response.path("movieId").asLong()).isEqualTo(movieId);
        assertThat(response.path("score").decimalValue()).isEqualByComparingTo("4.5");
        assertThat(response.path("comment").asText()).isEqualTo("좋았어요");
        assertThat(response.path("visibility").asText()).isEqualTo("PUBLIC");
        assertThat(response.path("spoiler").booleanValue()).isFalse();
        assertThat(response.path("watchStatus").asText()).isEqualTo("RATED");
        assertThat(createdAt).isBetween(before, after);
        assertThat(response.path("frame").path("createdAt").asText())
                .isEqualTo(createdAt.toString());
        assertThat(response.path("popcorn").path("flavorCode").asText()).isEqualTo("FLAVOR_TEST");
        assertThat(response.path("popcorn").path("flavorCount").asLong()).isEqualTo(1);
        assertThat(response.path("popcorn").path("isNewFlavor").asBoolean()).isTrue();

        RatingRow rating = rating(ratingId);
        assertThat(rating.userId()).isEqualTo(userId);
        assertThat(rating.movieId()).isEqualTo(movieId);
        assertThat(rating.score()).isEqualByComparingTo("4.5");
        assertThat(rating.comment()).isEqualTo("좋았어요");
        assertThat(rating.visibility()).isEqualTo("PUBLIC");
        assertThat(rating.createdAt()).isEqualTo(createdAt);
        assertThat(rating.watchRecordId()).isPositive();
        assertThat(watchStatus(rating.watchRecordId())).isEqualTo("RATED");
        assertThat(frameRatingId(frameId)).isEqualTo(ratingId);
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(1, "4.50"));
        assertEvent(onlyEvent(), userId, movieId, "4.5");
    }

    @Test
    @DisplayName("스포일러로 표시한 공개 한줄평은 응답과 DB에 true로 저장한다")
    void persistsExplicitTrueSpoiler() throws Exception {
        JsonNode response =
                create(
                        movieId,
                        userId,
                        "{\"score\":4.5,\"comment\":\"결말 반전\",\"visibility\":\"PUBLIC\",\"spoiler\":true}",
                        201);

        long ratingId = response.path("ratingId").asLong();
        assertThat(response.path("spoiler").isBoolean()).isTrue();
        assertThat(response.path("spoiler").booleanValue()).isTrue();
        assertThat(ratingSpoiler(ratingId)).isTrue();
        assertThat(rating(ratingId).comment()).isEqualTo("결말 반전");
        assertThat(count("watch_records")).isEqualTo(1);
        assertThat(count("frames")).isEqualTo(1);
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(1, "4.50"));
        assertEvent(onlyEvent(), userId, movieId, "4.5");
    }

    @Test
    @DisplayName("스포일러 필드를 생략하면 false를 응답하고 저장한다")
    void defaultsOmittedSpoilerToFalse() throws Exception {
        JsonNode response = create(movieId, userId, validBody(), 201);

        assertThat(response.path("spoiler").isBoolean()).isTrue();
        assertThat(response.path("spoiler").booleanValue()).isFalse();
        assertThat(ratingSpoiler(response.path("ratingId").asLong())).isFalse();
    }

    @Test
    @DisplayName("명시적으로 spoiler false를 보내면 응답과 DB에 false를 저장한다")
    void persistsExplicitFalseSpoiler() throws Exception {
        JsonNode response =
                create(
                        movieId,
                        userId,
                        "{\"score\":4.5,\"comment\":\"일반 한줄평\",\"visibility\":\"PUBLIC\",\"spoiler\":false}",
                        201);

        assertThat(response.path("spoiler").isBoolean()).isTrue();
        assertThat(response.path("spoiler").booleanValue()).isFalse();
        assertThat(ratingSpoiler(response.path("ratingId").asLong())).isFalse();
    }

    @Test
    @DisplayName("한줄평 없는 점수 평가도 기존처럼 생성하고 spoiler false를 반환한다")
    void acceptsScoreOnlyWithoutSpoiler() throws Exception {
        JsonNode response = create(movieId, userId, "{\"score\":4.5}", 201);

        assertThat(response.path("spoiler").booleanValue()).isFalse();
        assertThat(rating(response.path("ratingId").asLong()).comment()).isNull();
        assertThat(ratingSpoiler(response.path("ratingId").asLong())).isFalse();
        assertThat(count("frames")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    @DisplayName("한줄평 없는 spoiler true는 도메인 쓰기 전에 거절한다")
    void rejectsTrueWithoutCommentBeforeWrites() throws Exception {
        assertValidation(request(movieId, userId, "{\"score\":4.5,\"spoiler\":true}"));
        assertNoWrites();
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(0, "0.00"));
    }

    @ParameterizedTest(name = "comment={0}")
    @ValueSource(strings = {"", "\t\n", "\u00A0", "\u3000", "\u0001", "\u0085", "\u200B", "\u0000"})
    @DisplayName("공백만 있는 한줄평의 spoiler true는 저장하지 않는다")
    void rejectsTrueWithBlankCommentsBeforeWrites(String comment) throws Exception {
        String body =
                "{\"score\":4.5,\"comment\":"
                        + objectMapper.writeValueAsString(comment)
                        + ",\"visibility\":\"PUBLIC\",\"spoiler\":true}";
        assertValidation(request(movieId, userId, body));
        assertNoWrites();
    }

    @ParameterizedTest(name = "spoiler={0}")
    @ValueSource(strings = {"null", "\"true\"", "1", "[]", "{}"})
    @DisplayName("boolean 이외의 spoiler 값은 저장하지 않는다")
    void rejectsMalformedSpoilerTypesBeforeWrites(String spoiler) throws Exception {
        String body =
                "{\"score\":4.5,\"comment\":\"문구\",\"visibility\":\"PUBLIC\",\"spoiler\":"
                        + spoiler
                        + "}";
        assertValidation(request(movieId, userId, body));
        assertNoWrites();
    }

    @Test
    @DisplayName("스포일러 한줄평에도 공개 범위가 필요하다")
    void requiresVisibilityForSpoilerComment() throws Exception {
        assertValidation(
                request(movieId, userId, "{\"score\":4.5,\"comment\":\"반전\",\"spoiler\":true}"));
        assertNoWrites();
    }

    @Test
    @DisplayName("평가 생성으로 저장한 PUBLIC spoiler는 한줄평 조회에 원문과 true로 나온다")
    void publicReviewReturnsCreatedSpoiler() throws Exception {
        JsonNode created =
                create(
                        movieId,
                        userId,
                        "{\"score\":4.5,\"comment\":\"범인은 원문\",\"visibility\":\"PUBLIC\",\"spoiler\":true}",
                        201);

        JsonNode items = reviews(userId).path("items");
        assertThat(items.size()).isEqualTo(1);
        JsonNode item = items.path(0);
        assertThat(item.path("ratingId").asLong()).isEqualTo(created.path("ratingId").asLong());
        assertThat(item.path("comment").asText()).isEqualTo("범인은 원문");
        assertThat(item.path("spoiler").booleanValue()).isTrue();
        assertThat(item.path("mine").booleanValue()).isTrue();
    }

    @Test
    @DisplayName("PRIVATE spoiler는 작성자에게만 한줄평으로 노출한다")
    void privateSpoilerVisibleOnlyToAuthor() throws Exception {
        long otherUserId = insertUser("other-review@feelm.test", "다른 사용자", "ACTIVE");
        create(
                movieId,
                userId,
                "{\"score\":4.5,\"comment\":\"내 스포일러\",\"visibility\":\"PRIVATE\",\"spoiler\":true}",
                201);

        JsonNode ownItems = reviews(userId).path("items");
        assertThat(ownItems.size()).isEqualTo(1);
        assertThat(ownItems.path(0).path("mine").booleanValue()).isTrue();
        assertThat(ownItems.path(0).path("spoiler").booleanValue()).isTrue();
        assertThat(reviews(otherUserId).path("items").size()).isZero();
    }

    @Test
    @DisplayName("true 스포일러 생성 중 Outbox 실패 시 모든 저장을 롤백한다")
    void outboxFailureRollsBackTrueSpoiler() throws Exception {
        failingInsertTrigger("outbox_events", "test_fail_rating_outbox");

        request(movieId, userId, trueSpoilerBody())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertNoWrites();
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(0, "0.00"));
    }

    @Test
    @DisplayName("true 스포일러 생성 중 프레임 실패 시 모든 저장을 롤백한다")
    void frameFailureRollsBackTrueSpoiler() throws Exception {
        failingInsertTrigger("frames", "test_fail_rating_frame");

        request(movieId, userId, trueSpoilerBody())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertNoWrites();
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(0, "0.00"));
    }

    @Test
    @DisplayName("같은 영화의 true 스포일러 동시 생성은 한 건만 저장한다")
    void concurrentDuplicateSpoilerCreatesOnce() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first =
                    executor.submit(() -> concurrentCreate(start, trueSpoilerBody()));
            Future<Integer> second =
                    executor.submit(() -> concurrentCreate(start, trueSpoilerBody()));
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }

        assertThat(count("ratings")).isEqualTo(1);
        assertThat(count("frames")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT is_spoiler FROM ratings").query(Boolean.class).single())
                .isTrue();
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(1, "4.50"));
    }

    @Test
    @DisplayName("스포일러 표시는 RATING CREATE 이벤트 payload를 변경하지 않는다")
    void ratingEventUnchangedBySpoiler() throws Exception {
        create(movieId, userId, trueSpoilerBody(), 201);

        JsonNode payload = objectMapper.readTree(onlyEvent().message()).path("payload");
        assertThat(payload.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("action", "movieId", "score");
        assertThat(payload.path("score").decimalValue()).isEqualByComparingTo("4.5");
    }

    @Test
    @DisplayName("감상 기록 없이 평가하면 RATED 기록을 만들고 연결한다")
    void createWithoutPriorWatchCreatesRatedRecord() throws Exception {
        JsonNode response =
                create(movieId, userId, "{\"score\":3.0,\"visibility\":\"PRIVATE\"}", 201);

        RatingRow rating = rating(response.path("ratingId").asLong());
        assertThat(count("watch_records")).isEqualTo(1);
        assertThat(watchStatus(rating.watchRecordId())).isEqualTo("RATED");
        assertThat(count("frames")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    @DisplayName("LINK_CLICKED 감상 기록은 같은 ID로 RATED 전이한다")
    void createFromLinkClickedTransitionsExistingWatch() throws Exception {
        long watchRecordId = insertLinkClickedWatch(userId, movieId);

        JsonNode response =
                create(movieId, userId, "{\"score\":4.0,\"visibility\":\"PRIVATE\"}", 201);

        RatingRow rating = rating(response.path("ratingId").asLong());
        assertThat(rating.watchRecordId()).isEqualTo(watchRecordId);
        assertThat(watchStatus(watchRecordId)).isEqualTo("RATED");
        assertThat(count("watch_records")).isEqualTo(1);
    }

    @ParameterizedTest(name = "score={0}")
    @ValueSource(strings = {"0", "5.5", "4.49", "4.49999999999999999", "4.50000000000000001"})
    @DisplayName("범위 또는 0.5 단위를 벗어난 점수는 저장하지 않는다")
    void rejectsInvalidScoresWithoutWrites(String score) throws Exception {
        assertValidation(
                request(movieId, userId, "{\"score\":" + score + ",\"visibility\":\"PRIVATE\"}"));
        assertNoWrites();
    }

    @Test
    @DisplayName("필수 점수 누락은 저장하지 않는다")
    void rejectsMissingScoreWithoutWrites() throws Exception {
        assertValidation(request(movieId, userId, "{\"visibility\":\"PRIVATE\"}"));
        assertNoWrites();
    }

    @Test
    @DisplayName("한줄평 길이와 공개 범위를 검증한다")
    void validatesCommentAndVisibility() throws Exception {
        assertValidation(request(movieId, userId, "{\"score\":4.5,\"comment\":\"한줄평\"}"));
        assertNoWrites();

        String longComment = "가".repeat(201);
        assertValidation(
                request(
                        movieId,
                        userId,
                        "{\"score\":4.5,\"comment\":\""
                                + longComment
                                + "\",\"visibility\":\"PUBLIC\"}"));
        assertNoWrites();

        assertValidation(request(movieId, userId, "{\"score\":4.5,\"visibility\":\"FRIENDS\"}"));
        assertNoWrites();
    }

    @Test
    @DisplayName("없는 영화는 404이고 아무것도 저장하지 않는다")
    void rejectsMissingMovie() throws Exception {
        request(9_999_999, userId, validBody())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertNoWrites();
    }

    @Test
    @DisplayName("비활성 또는 없는 인증 사용자는 401이다")
    void rejectsInactiveOrMissingUser() throws Exception {
        request(movieId, withdrawnUserId, validBody())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        request(movieId, 9_999_999, validBody())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertNoWrites();
    }

    @Test
    @DisplayName("중복 평가는 409이고 두 번째 이벤트를 만들지 않는다")
    void duplicateRatingReturnsConflictWithoutSecondEvent() throws Exception {
        create(movieId, userId, validBody(), 201);

        request(movieId, userId, validBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RATING_ALREADY_EXISTS"));

        assertThat(count("ratings")).isEqualTo(1);
        assertThat(count("frames")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(1, "4.50"));
    }

    @Test
    @DisplayName("Outbox 저장 실패는 평점·감상·프레임·집계를 모두 롤백한다")
    void outboxFailureRollsBackRatingWatchAndFrame() throws Exception {
        failingInsertTrigger("outbox_events", "test_fail_rating_outbox");

        request(movieId, userId, validBody())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertNoWrites();
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(0, "0.00"));
    }

    @Test
    @DisplayName("프레임 저장 실패는 평점·감상·집계·Outbox를 모두 롤백한다")
    void frameFailureRollsBackRatingWatchAndOutbox() throws Exception {
        failingInsertTrigger("frames", "test_fail_rating_frame");

        request(movieId, userId, validBody())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertNoWrites();
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(0, "0.00"));
    }

    @Test
    @DisplayName("동시 중복 요청은 평점·프레임·이벤트와 영화 집계를 한 번만 만든다")
    void concurrentDuplicateCreatesExactlyOneRatingAndEvent() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> concurrentCreate(start));
            Future<Integer> second = executor.submit(() -> concurrentCreate(start));
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }

        assertThat(count("ratings")).isEqualTo(1);
        assertThat(count("frames")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(1, "4.50"));
    }

    @Test
    @DisplayName("RATING 이벤트는 4.5점을 절삭하지 않고 보존한다")
    void ratingEventPreservesHalfStepScore() throws Exception {
        create(movieId, userId, validBody(), 201);
        assertEvent(onlyEvent(), userId, movieId, "4.5");
    }

    @Test
    @DisplayName("같은 대표 맛의 두 번째 평가는 새 맛이 아니고 flavorCount가 증가한다")
    void secondRatingForFlavorIsNotNew() throws Exception {
        create(movieId, userId, validBody(), 201);

        JsonNode response =
                create(otherMovieId, userId, "{\"score\":3.5,\"visibility\":\"PRIVATE\"}", 201);

        assertThat(response.path("popcorn").path("flavorCount").asLong()).isEqualTo(2);
        assertThat(response.path("popcorn").path("isNewFlavor").asBoolean()).isFalse();
    }

    private int concurrentCreate(CountDownLatch start) throws Exception {
        return concurrentCreate(start, validBody());
    }

    private int concurrentCreate(CountDownLatch start, String body) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return request(movieId, userId, body).andReturn().getResponse().getStatus();
    }

    private JsonNode create(long movie, long user, String body, int expectedStatus)
            throws Exception {
        MvcResult result =
                request(movie, user, body)
                        .andExpect(status().is(expectedStatus))
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andReturn();
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions request(long movie, long user, String body) throws Exception {
        return mockMvc.perform(
                post("/api/v1/movies/{movieId}/ratings", movie)
                        .header("Authorization", "Bearer " + tokenProvider.createAccessToken(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private void assertValidation(ResultActions result) throws Exception {
        String body =
                result.andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                        .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).doesNotContain("Exception", "SQL", "java.", "org.postgresql");
    }

    private void assertNoWrites() {
        assertThat(count("ratings")).isZero();
        assertThat(count("watch_records")).isZero();
        assertThat(count("frames")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    private String validBody() {
        return "{\"score\":4.5,\"visibility\":\"PRIVATE\"}";
    }

    private String trueSpoilerBody() {
        return "{\"score\":4.5,\"comment\":\"반전\",\"visibility\":\"PUBLIC\",\"spoiler\":true}";
    }

    private JsonNode reviews(long user) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get("/api/v1/movies/{movieId}/reviews", movieId)
                                        .header(
                                                "Authorization",
                                                "Bearer " + tokenProvider.createAccessToken(user)))
                        .andExpect(status().isOk())
                        .andReturn();
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void assertEvent(EventRow event, long user, long movie, String score) {
        assertThatCode(() -> UUID.fromString(event.eventId())).doesNotThrowAnyException();
        assertThat(event.eventType()).isEqualTo("RATING");
        assertThat(event.topic()).isEqualTo("feelm.rating.v1");
        assertThat(event.partitionKey()).isEqualTo(user);
        assertThat(event.status()).isEqualTo("PENDING");
        try {
            JsonNode envelope = objectMapper.readTree(event.message());
            assertThat(envelope.path("eventId").asText()).isEqualTo(event.eventId());
            assertThat(envelope.path("eventType").asText()).isEqualTo("RATING");
            assertThat(envelope.path("userId").asLong()).isEqualTo(user);
            assertThat(OffsetDateTime.parse(envelope.path("occurredAt").asText()).getOffset())
                    .isEqualTo(ZoneOffset.UTC);
            JsonNode payload = envelope.path("payload");
            assertThat(payload.path("action").asText()).isEqualTo("CREATE");
            assertThat(payload.path("movieId").asLong()).isEqualTo(movie);
            assertThat(payload.path("score").decimalValue()).isEqualByComparingTo(score);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private EventRow onlyEvent() {
        return jdbc.sql(
                        """
                        SELECT event_id::text, event_type, topic, partition_key,
                               status, message::text
                        FROM outbox_events
                        """)
                .query(
                        (row, number) ->
                                new EventRow(
                                        row.getString("event_id"),
                                        row.getString("event_type"),
                                        row.getString("topic"),
                                        row.getLong("partition_key"),
                                        row.getString("status"),
                                        row.getString("message")))
                .single();
    }

    private RatingRow rating(long id) {
        return jdbc.sql(
                        """
                        SELECT user_id, movie_id, watch_record_id, score, comment,
                               visibility, created_at
                        FROM ratings WHERE id=:id
                        """)
                .param("id", id)
                .query(
                        (row, number) ->
                                new RatingRow(
                                        row.getLong("user_id"),
                                        row.getLong("movie_id"),
                                        row.getLong("watch_record_id"),
                                        row.getBigDecimal("score"),
                                        row.getString("comment"),
                                        row.getString("visibility"),
                                        row.getObject("created_at", OffsetDateTime.class)
                                                .toInstant()))
                .single();
    }

    private boolean ratingSpoiler(long id) {
        return jdbc.sql("SELECT is_spoiler FROM ratings WHERE id=:id")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    private MovieAggregate movieAggregate(long movie) {
        return jdbc.sql("SELECT rating_count, average_rating::text FROM movies WHERE id=:movie")
                .param("movie", movie)
                .query(
                        (row, number) ->
                                new MovieAggregate(
                                        row.getInt("rating_count"),
                                        row.getString("average_rating")))
                .single();
    }

    private String watchStatus(long id) {
        return jdbc.sql("SELECT status FROM watch_records WHERE id=:id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private long frameRatingId(long id) {
        return jdbc.sql("SELECT rating_id FROM frames WHERE id=:id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private long insertFlavor(String code) {
        return jdbc.sql(
                        """
                        INSERT INTO popcorn_flavors(
                            code, display_name, color, sort_order,
                            cluster_id, representative_keywords
                        ) VALUES (
                            :code, '테스트 맛', '#123456', 1,
                            0, '[]'::jsonb
                        ) RETURNING id
                        """)
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private long insertUser(String email, String nickname, String userStatus) {
        return jdbc.sql(
                        """
                        INSERT INTO users(email,nickname,birth_date,gender,status)
                        VALUES (:email,:nickname,DATE '2000-01-01','OTHER',:status)
                        RETURNING id
                        """)
                .param("email", email)
                .param("nickname", nickname)
                .param("status", userStatus)
                .query(Long.class)
                .single();
    }

    private long insertMovie(int tmdbId, String title, long representativeFlavorId) {
        return jdbc.sql(
                        """
                        INSERT INTO movies(tmdb_id,title,representative_flavor_id)
                        VALUES (:tmdbId,:title,:flavorId) RETURNING id
                        """)
                .param("tmdbId", tmdbId)
                .param("title", title)
                .param("flavorId", representativeFlavorId)
                .query(Long.class)
                .single();
    }

    private long insertLinkClickedWatch(long user, long movie) {
        Instant clicked = Instant.now().minusSeconds(60);
        return jdbc.sql(
                        """
                        INSERT INTO watch_records(
                            user_id,movie_id,status,clicked_at,confirm_due_at,expires_at
                        ) VALUES (
                            :user,:movie,'LINK_CLICKED',:clicked,:confirmDue,:expires
                        ) RETURNING id
                        """)
                .param("user", user)
                .param("movie", movie)
                .param("clicked", clicked.atOffset(ZoneOffset.UTC))
                .param("confirmDue", clicked.plusSeconds(172_800).atOffset(ZoneOffset.UTC))
                .param("expires", clicked.plusSeconds(604_800).atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    private void failingInsertTrigger(String table, String name) {
        jdbc.sql(
                        """
                        CREATE FUNCTION %s() RETURNS trigger AS $$
                        BEGIN RAISE EXCEPTION 'forced test failure'; END;
                        $$ LANGUAGE plpgsql
                        """
                                .formatted(name))
                .update();
        jdbc.sql(
                        "CREATE TRIGGER "
                                + name
                                + " BEFORE INSERT ON "
                                + table
                                + " FOR EACH ROW EXECUTE FUNCTION "
                                + name
                                + "()")
                .update();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private void truncate() {
        jdbc.sql(
                        "TRUNCATE TABLE outbox_events, frames, ratings, watch_records,"
                                + " movies, popcorn_flavors, users RESTART IDENTITY CASCADE")
                .update();
    }

    private record RatingRow(
            long userId,
            long movieId,
            long watchRecordId,
            java.math.BigDecimal score,
            String comment,
            String visibility,
            Instant createdAt) {}

    private record MovieAggregate(int count, String average) {}

    private record EventRow(
            String eventId,
            String eventType,
            String topic,
            long partitionKey,
            String status,
            String message) {}
}
