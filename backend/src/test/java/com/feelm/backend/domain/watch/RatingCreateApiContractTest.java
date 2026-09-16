package com.feelm.backend.domain.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
                        "frame",
                        "popcorn",
                        "watchStatus",
                        "createdAt");
        assertThat(response.path("movieId").asLong()).isEqualTo(movieId);
        assertThat(response.path("score").decimalValue()).isEqualByComparingTo("4.5");
        assertThat(response.path("comment").asText()).isEqualTo("좋았어요");
        assertThat(response.path("visibility").asText()).isEqualTo("PUBLIC");
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
    @ValueSource(strings = {"0", "5.5", "4.49"})
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
        start.await(5, TimeUnit.SECONDS);
        return request(movieId, userId, validBody()).andReturn().getResponse().getStatus();
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
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
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
