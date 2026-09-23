package com.feelm.backend.domain.onboarding;

import static com.feelm.backend.domain.onboarding.OnboardingFixture.ACTIVE;
import static com.feelm.backend.domain.onboarding.OnboardingFixture.COMPLETED;
import static com.feelm.backend.domain.onboarding.OnboardingFixture.OTHER;
import static com.feelm.backend.domain.onboarding.OnboardingFixture.WITHDRAWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.auth.PasswordResetTokenIssuer;
import com.feelm.backend.domain.auth.RefreshTokenService;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * API-024: 실제 마이그레이션과 PostgreSQL을 사용하는 초기 취향 제출 HTTP 계약 테스트.
 *
 * <p>아웃박스 Relay는 테스트 공통 설정({@code feelm.kafka.outbox.relay-enabled=false})으로 꺼져 있어 검증 대상 행의 상태를 바꾸지
 * 않는다.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@DisplayName("API-024 초기 취향 제출")
class OnboardingSubmitApiContractTest extends SharedPostgresContractTest {

    private static final String SUBMIT_PATH = "/api/v1/onboarding/preferences";
    private static final long LIKED_MOVIE = 101;
    private static final long DISLIKED_MOVIE = 201;
    private static final String CURRENT_PASSWORD = "Cur7ent@Pass";
    private static final String NEW_PASSWORD = "N3wP@ssw0rd";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;

    /**
     * T24에서 비밀번호 변경 트랜잭션을 사전 검사와 계정 행 잠금 사이에 세우기 위한 spy. {@link SharedPostgresContractTest}가 등록하며
     * 기본 동작은 실제 빈과 같다.
     */
    @Autowired PasswordResetTokenIssuer resetTokenIssuer;

    /** 세션 폐기 spy. 폐기 자체는 API-012 계약 테스트가 보고, 여기서 보는 것은 비밀번호 변경이 계정 행에 남기는 결과다. T24에서만 폐기를 비운다. */
    @Autowired RefreshTokenService refreshTokenService;

    private ExecutorService executor;

    @BeforeEach
    void fixtures() {
        dropFailingFrameTrigger();
        OnboardingFixture.reset(jdbc);
        OnboardingFixture.flavors(jdbc);
        OnboardingFixture.movie(jdbc, LIKED_MOVIE, 1L, 900);
        OnboardingFixture.movie(jdbc, DISLIKED_MOVIE, 2L, 800);
        OnboardingFixture.preference(jdbc, OTHER, LIKED_MOVIE, "LIKE");
    }

    /** 어떤 경로도 다른 사용자의 선택을 건드리지 않는다(T20). */
    @AfterEach
    void keepsOtherUserPreferences() {
        assertThat(preferencesOf(OTHER)).containsExactly(Map.entry(LIKED_MOVIE, "LIKE"));
    }

    @AfterEach
    void stopExecutor() throws InterruptedException {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @AfterEach
    void removeFailureTrigger() {
        dropFailingFrameTrigger();
    }

    @Test
    @DisplayName("T10 선택을 평가·프레임·팝콘·감상 기록으로 저장하고 영화 집계를 갱신한다")
    void createsRatingsFramesPopcornWatchesAndMovieAggregates() throws Exception {
        submit(ACTIVE, bothChoices())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingCompleted").value(true))
                .andExpect(jsonPath("$.savedCount").value(2))
                .andExpect(jsonPath("$.skipped").value(false));

        assertThat(preferencesOf(ACTIVE))
                .containsExactly(
                        Map.entry(LIKED_MOVIE, "LIKE"), Map.entry(DISLIKED_MOVIE, "DISLIKE"));
        assertThat(onboardingCompleted(ACTIVE)).isTrue();
        assertThat(ratingsOf(ACTIVE))
                .containsExactly(
                        Map.entry(LIKED_MOVIE, new BigDecimal("4.0")),
                        Map.entry(DISLIKED_MOVIE, new BigDecimal("2.0")));
        assertThat(ratedWatchMoviesOf(ACTIVE)).containsExactly(LIKED_MOVIE, DISLIKED_MOVIE);
        assertThat(frameMoviesOf(ACTIVE)).containsExactly(LIKED_MOVIE, DISLIKED_MOVIE);
        assertThat(popcornCount(ACTIVE, 1L)).isEqualTo(1);
        assertThat(popcornCount(ACTIVE, 2L)).isEqualTo(1);
        assertThat(movieAggregate(LIKED_MOVIE)).isEqualTo(Map.entry(1, new BigDecimal("4.00")));
        assertThat(movieAggregate(DISLIKED_MOVIE)).isEqualTo(Map.entry(1, new BigDecimal("2.00")));
    }

    @Test
    @DisplayName("T25 기존 평가 영화는 덮어쓰지 않고 새 영화만 평가로 만든다")
    void skipsMovieWithExistingRatingWithoutOverwritingAnything() throws Exception {
        ExistingRating existing = existingRating(ACTIVE, LIKED_MOVIE, "3.5");
        ExistingRatingState ratingBefore = ratingState(existing.ratingId());
        ExistingWatchState watchBefore = watchState(existing.watchId());
        ExistingFrameState frameBefore = frameState(existing.ratingId());

        submit(ACTIVE, bothChoices())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedCount").value(2));

        assertThat(ratingsOf(ACTIVE))
                .containsExactly(
                        Map.entry(LIKED_MOVIE, new BigDecimal("3.5")),
                        Map.entry(DISLIKED_MOVIE, new BigDecimal("2.0")));
        assertThat(ratingState(existing.ratingId())).isEqualTo(ratingBefore);
        assertThat(watchState(existing.watchId())).isEqualTo(watchBefore);
        assertThat(frameState(existing.ratingId())).isEqualTo(frameBefore);
        assertThat(ratedWatchMoviesOf(ACTIVE)).containsExactly(LIKED_MOVIE, DISLIKED_MOVIE);
        assertThat(frameMoviesOf(ACTIVE)).containsExactly(LIKED_MOVIE, DISLIKED_MOVIE);
        assertThat(movieAggregate(LIKED_MOVIE)).isEqualTo(Map.entry(1, new BigDecimal("3.50")));
        assertThat(popcornCount(ACTIVE, 1L)).isEqualTo(1);
        assertThat(popcornCount(ACTIVE, 2L)).isEqualTo(1);
    }

    @Test
    @DisplayName("T11 저장한 선택 수를 담은 ONBOARDING 이벤트를 발행한다")
    void publishesOnboardingEvent() throws Exception {
        submit(ACTIVE, bothChoices()).andExpect(status().isOk());

        List<Map<String, Object>> events = outboxEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("event_type")).isEqualTo("ONBOARDING");
        assertThat(events.get(0).get("topic")).isEqualTo("feelm.rating.v1");
        assertThat(((Number) events.get(0).get("partition_key")).longValue()).isEqualTo(ACTIVE);
        // jsonb는 저장 시 키 순서와 공백이 정규화되므로 문자열이 아니라 파싱한 값으로 본다.
        JsonNode message = objectMapper.readTree(events.get(0).get("message").toString());
        assertThat(message.path("eventType").asText()).isEqualTo("ONBOARDING");
        assertThat(message.path("userId").asLong()).isEqualTo(ACTIVE);
        assertThat(message.path("eventId").asText()).isNotBlank();
        assertThat(message.path("payload").path("preferenceCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("T12 건너뛴 제출은 아무것도 저장하지 않고 이벤트도 발행하지 않는다")
    void skippedSubmissionCreatesNoRatingArtifacts() throws Exception {
        submit(ACTIVE, "{\"preferences\":[],\"skipped\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingCompleted").value(true))
                .andExpect(jsonPath("$.savedCount").value(0))
                .andExpect(jsonPath("$.skipped").value(true));

        assertThat(preferencesOf(ACTIVE)).isEmpty();
        assertThat(onboardingCompleted(ACTIVE)).isTrue();
        assertThat(outboxEvents()).isEmpty();
        assertThat(ratingsOf(ACTIVE)).isEmpty();
        assertThat(ratedWatchMoviesOf(ACTIVE)).isEmpty();
        assertThat(frameMoviesOf(ACTIVE)).isEmpty();
    }

    @Test
    @DisplayName("T26 두 번째 프레임 저장 실패는 온보딩의 모든 상태와 집계를 롤백한다")
    void frameFailureRollsBackAllOnboardingWrites() throws Exception {
        failFrameInsertFor(DISLIKED_MOVIE);

        submit(ACTIVE, bothChoices())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(preferencesOf(ACTIVE)).isEmpty();
        assertThat(onboardingCompleted(ACTIVE)).isFalse();
        assertThat(ratingsOf(ACTIVE)).isEmpty();
        assertThat(ratedWatchMoviesOf(ACTIVE)).isEmpty();
        assertThat(frameMoviesOf(ACTIVE)).isEmpty();
        assertThat(outboxEvents()).isEmpty();
        assertThat(movieAggregate(LIKED_MOVIE)).isEqualTo(Map.entry(900, new BigDecimal("4.50")));
        assertThat(movieAggregate(DISLIKED_MOVIE))
                .isEqualTo(Map.entry(800, new BigDecimal("4.50")));
    }

    @Test
    @DisplayName("T13 같은 영화를 두 번 선택하면 거절한다")
    void rejectsDuplicateMovieId() throws Exception {
        submit(
                        ACTIVE,
                        "{\"preferences\":["
                                + choice(LIKED_MOVIE, "LIKE")
                                + ","
                                + choice(LIKED_MOVIE, "DISLIKE")
                                + "],\"skipped\":false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(preferencesOf(ACTIVE)).isEmpty();
        assertThat(onboardingCompleted(ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("T14 정의되지 않은 preference 값은 거절한다")
    void rejectsUnknownPreferenceValue() throws Exception {
        submit(ACTIVE, "{\"preferences\":[" + choice(LIKED_MOVIE, "MAYBE") + "],\"skipped\":false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(preferencesOf(ACTIVE)).isEmpty();
    }

    @Test
    @DisplayName("T15 건너뛴 제출에 선택이 들어 있으면 거절한다")
    void rejectsSkippedWithPreferences() throws Exception {
        submit(ACTIVE, "{\"preferences\":[" + choice(LIKED_MOVIE, "LIKE") + "],\"skipped\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(preferencesOf(ACTIVE)).isEmpty();
        assertThat(onboardingCompleted(ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("T16 존재하지 않는 영화를 선택하면 거절하고 완료 처리하지 않는다")
    void unknownMovieRollsBackAllRatingArtifacts() throws Exception {
        submit(
                        ACTIVE,
                        "{\"preferences\":["
                                + choice(LIKED_MOVIE, "LIKE")
                                + ","
                                + choice(999, "LIKE")
                                + "],\"skipped\":false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(preferencesOf(ACTIVE)).isEmpty();
        assertThat(onboardingCompleted(ACTIVE)).isFalse();
        assertThat(outboxEvents()).isEmpty();
        assertThat(ratingsOf(ACTIVE)).isEmpty();
        assertThat(ratedWatchMoviesOf(ACTIVE)).isEmpty();
        assertThat(frameMoviesOf(ACTIVE)).isEmpty();
        assertThat(movieAggregate(LIKED_MOVIE)).isEqualTo(Map.entry(900, new BigDecimal("4.50")));
        assertThat(movieAggregate(DISLIKED_MOVIE))
                .isEqualTo(Map.entry(800, new BigDecimal("4.50")));
    }

    @Test
    @DisplayName("T17 이미 온보딩을 마쳤으면 다시 제출할 수 없다")
    void rejectsSecondSubmit() throws Exception {
        OnboardingFixture.preference(jdbc, COMPLETED, LIKED_MOVIE, "LIKE");

        submit(COMPLETED, bothChoices())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ONBOARDING_ALREADY_COMPLETED"));

        assertThat(preferencesOf(COMPLETED)).containsExactly(Map.entry(LIKED_MOVIE, "LIKE"));
        assertThat(onboardingCompleted(COMPLETED)).isTrue();
    }

    @Test
    @DisplayName("T18 활성 계정이 아니면 제출할 수 없다")
    void rejectsInactiveUser() throws Exception {
        submit(WITHDRAWN, bothChoices())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(preferencesOf(WITHDRAWN)).isEmpty();
        assertThat(onboardingCompleted(WITHDRAWN)).isFalse();
    }

    @Test
    @DisplayName("T19 같은 사용자의 동시 제출은 하나만 성공한다")
    void serializesConcurrentSubmits() throws Exception {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        String token = bearer(ACTIVE);

        List<Future<Integer>> responses =
                IntStream.range(0, 2)
                        .mapToObj(
                                index ->
                                        executor.submit(
                                                () ->
                                                        concurrentSubmit(
                                                                bothChoices(),
                                                                token,
                                                                ready,
                                                                start)))
                        .toList();
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<Integer> statuses = List.of(responses.get(0).get(), responses.get(1).get());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(preferencesOf(ACTIVE)).hasSize(2);
        assertThat(onboardingCompleted(ACTIVE)).isTrue();
        assertThat(outboxEvents()).hasSize(1);
        assertThat(ratingsOf(ACTIVE)).hasSize(2);
        assertThat(ratedWatchMoviesOf(ACTIVE)).hasSize(2);
        assertThat(frameMoviesOf(ACTIVE)).hasSize(2);
    }

    @Test
    @DisplayName("T21 preference를 문자열이 아닌 값으로 보내면 거절한다")
    void rejectsNonStringPreference() throws Exception {
        for (String preference : List.of("0", "\"0\"", "1", "true")) {
            submit(
                            ACTIVE,
                            item(
                                    "{\"movieId\":"
                                            + LIKED_MOVIE
                                            + ",\"preference\":"
                                            + preference
                                            + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        assertThat(preferencesOf(ACTIVE)).isEmpty();
        assertThat(onboardingCompleted(ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("T22 movieId를 JSON 정수가 아닌 값으로 보내면 거절한다")
    void rejectsNonIntegerMovieId() throws Exception {
        for (String movieId : List.of("101.9", "\"101\"", "1.0e2")) {
            submit(ACTIVE, item("{\"movieId\":" + movieId + ",\"preference\":\"LIKE\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        assertThat(preferencesOf(ACTIVE)).isEmpty();
        assertThat(onboardingCompleted(ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("T23 skipped를 boolean이 아닌 값으로 보내면 거절한다")
    void rejectsNonBooleanSkipped() throws Exception {
        for (String skipped : List.of("1", "\"true\"", "0")) {
            submit(ACTIVE, "{\"preferences\":[],\"skipped\":" + skipped + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        assertThat(onboardingCompleted(ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("T24 비밀번호 변경과 교차 실행돼도 온보딩 완료 상태가 남는다")
    void keepsOnboardingCompletedAcrossPasswordChange() throws Exception {
        jdbc.update(
                "UPDATE users SET password_hash=? WHERE id=?",
                passwordEncoder.encode(CURRENT_PASSWORD),
                ACTIVE);
        doNothing().when(refreshTokenService).revokeAll(ACTIVE);
        executor = Executors.newSingleThreadExecutor();
        CountDownLatch inWindow = new CountDownLatch(1);
        CountDownLatch onboarded = new CountDownLatch(1);
        // 비밀번호 변경의 사전 검사와 계정 행 잠금 사이에서 멈춘다. 그 사이에 온보딩이 커밋되는 것이 재현 조건이다.
        doAnswer(
                        invocation -> {
                            inWindow.countDown();
                            assertThat(onboarded.await(5, TimeUnit.SECONDS)).isTrue();
                            return invocation.callRealMethod();
                        })
                .when(resetTokenIssuer)
                .invalidateAllUnused(ACTIVE);

        Future<Integer> passwordChange = executor.submit(this::changePassword);
        assertThat(inWindow.await(5, TimeUnit.SECONDS)).isTrue();
        submit(ACTIVE, bothChoices()).andExpect(status().isOk());
        onboarded.countDown();

        assertThat(passwordChange.get()).isEqualTo(204);
        // 비밀번호 변경이 제 일을 했는지 먼저 본다. 실패하면 아래 단언이 거짓으로 통과한다.
        assertThat(passwordEncoder.matches(NEW_PASSWORD, passwordHashOf(ACTIVE))).isTrue();
        assertThat(onboardingCompleted(ACTIVE)).isTrue();
        assertThat(preferencesOf(ACTIVE)).hasSize(2);
    }

    private String item(String choice) {
        return "{\"preferences\":[" + choice + "],\"skipped\":false}";
    }

    private int changePassword() throws Exception {
        return mvc.perform(
                        put("/api/v1/users/me/password")
                                .header("Authorization", bearer(ACTIVE))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"currentPassword\":\""
                                                + CURRENT_PASSWORD
                                                + "\",\"newPassword\":\""
                                                + NEW_PASSWORD
                                                + "\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String passwordHashOf(long userId) {
        return jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE id=?", String.class, userId);
    }

    private String bothChoices() {
        return "{\"preferences\":["
                + choice(LIKED_MOVIE, "LIKE")
                + ","
                + choice(DISLIKED_MOVIE, "DISLIKE")
                + "],\"skipped\":false}";
    }

    private String choice(long movieId, String preference) {
        return "{\"movieId\":" + movieId + ",\"preference\":\"" + preference + "\"}";
    }

    private ResultActions submit(long userId, String body) throws Exception {
        return mvc.perform(
                post(SUBMIT_PATH)
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private int concurrentSubmit(
            String body, String token, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return mvc.perform(
                        post(SUBMIT_PATH)
                                .header("Authorization", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    private List<Map.Entry<Long, String>> preferencesOf(long userId) {
        return jdbc.query(
                "SELECT movie_id, preference FROM onboarding_preferences"
                        + " WHERE user_id=? ORDER BY movie_id",
                (row, rowNumber) -> Map.entry(row.getLong("movie_id"), row.getString("preference")),
                userId);
    }

    private boolean onboardingCompleted(long userId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT onboarding_completed FROM users WHERE id=?",
                        Boolean.class,
                        userId));
    }

    private List<Map<String, Object>> outboxEvents() {
        return jdbc.queryForList(
                "SELECT event_type, topic, partition_key, message::text AS message"
                        + " FROM outbox_events ORDER BY id");
    }

    private List<Map.Entry<Long, BigDecimal>> ratingsOf(long userId) {
        return jdbc.query(
                "SELECT movie_id, score FROM ratings WHERE user_id=? ORDER BY movie_id",
                (row, rowNumber) -> Map.entry(row.getLong("movie_id"), row.getBigDecimal("score")),
                userId);
    }

    private List<Long> ratedWatchMoviesOf(long userId) {
        return jdbc.queryForList(
                "SELECT movie_id FROM watch_records WHERE user_id=? AND status='RATED'"
                        + " ORDER BY movie_id",
                Long.class,
                userId);
    }

    private List<Long> frameMoviesOf(long userId) {
        return jdbc.queryForList(
                "SELECT movie_id FROM frames WHERE user_id=? ORDER BY movie_id",
                Long.class,
                userId);
    }

    private long popcornCount(long userId, long flavorId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ratings rating JOIN movies movie"
                        + " ON movie.id=rating.movie_id WHERE rating.user_id=?"
                        + " AND movie.representative_flavor_id=?",
                Long.class,
                userId,
                flavorId);
    }

    private Map.Entry<Integer, BigDecimal> movieAggregate(long movieId) {
        return jdbc.queryForObject(
                "SELECT rating_count, average_rating FROM movies WHERE id=?",
                (row, rowNumber) ->
                        Map.entry(row.getInt("rating_count"), row.getBigDecimal("average_rating")),
                movieId);
    }

    private ExistingRating existingRating(long userId, long movieId, String score) {
        long watchId =
                jdbc.queryForObject(
                        "INSERT INTO watch_records(user_id,movie_id,status,rated_at)"
                                + " VALUES (?,?,'RATED',TIMESTAMPTZ '2026-09-01 01:00:00Z')"
                                + " RETURNING id",
                        Long.class,
                        userId,
                        movieId);
        long ratingId =
                jdbc.queryForObject(
                        "INSERT INTO ratings(user_id,movie_id,watch_record_id,score,comment,"
                                + " visibility,is_spoiler,created_at,updated_at)"
                                + " VALUES (?,?,?,?,'기존 평가','PUBLIC',true,"
                                + " TIMESTAMPTZ '2026-09-01 01:00:01Z',"
                                + " TIMESTAMPTZ '2026-09-01 01:00:02Z') RETURNING id",
                        Long.class,
                        userId,
                        movieId,
                        watchId,
                        new BigDecimal(score));
        long frameId =
                jdbc.queryForObject(
                        "INSERT INTO frames(rating_id,user_id,movie_id,created_at)"
                                + " VALUES (?,?,?,TIMESTAMPTZ '2026-09-01 01:00:03Z') RETURNING id",
                        Long.class,
                        ratingId,
                        userId,
                        movieId);
        jdbc.update(
                "UPDATE movies SET rating_count=1, average_rating=? WHERE id=?",
                new BigDecimal(score),
                movieId);
        return new ExistingRating(ratingId, watchId, frameId);
    }

    private ExistingRatingState ratingState(long ratingId) {
        return jdbc.queryForObject(
                "SELECT score, comment, visibility, is_spoiler, watch_record_id,"
                        + " created_at, updated_at FROM ratings WHERE id=?",
                (row, rowNumber) ->
                        new ExistingRatingState(
                                row.getBigDecimal("score"),
                                row.getString("comment"),
                                row.getString("visibility"),
                                row.getBoolean("is_spoiler"),
                                row.getLong("watch_record_id"),
                                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                                row.getObject("updated_at", OffsetDateTime.class).toInstant()),
                ratingId);
    }

    private ExistingWatchState watchState(long watchId) {
        return jdbc.queryForObject(
                "SELECT status, rated_at FROM watch_records WHERE id=?",
                (row, rowNumber) ->
                        new ExistingWatchState(
                                row.getString("status"),
                                row.getObject("rated_at", OffsetDateTime.class).toInstant()),
                watchId);
    }

    private ExistingFrameState frameState(long ratingId) {
        return jdbc.queryForObject(
                "SELECT id, created_at FROM frames WHERE rating_id=?",
                (row, rowNumber) ->
                        new ExistingFrameState(
                                row.getLong("id"),
                                row.getObject("created_at", OffsetDateTime.class).toInstant()),
                ratingId);
    }

    private void failFrameInsertFor(long movieId) {
        jdbc.execute(
                """
                CREATE FUNCTION test_fail_onboarding_frame() RETURNS trigger AS $$
                BEGIN
                    IF NEW.movie_id = %d THEN
                        RAISE EXCEPTION 'forced onboarding frame failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """
                        .formatted(movieId));
        jdbc.execute(
                """
                CREATE TRIGGER test_fail_onboarding_frame
                BEFORE INSERT ON frames
                FOR EACH ROW EXECUTE FUNCTION test_fail_onboarding_frame()
                """);
    }

    private void dropFailingFrameTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_onboarding_frame ON frames");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_onboarding_frame()");
    }

    private record ExistingRating(long ratingId, long watchId, long frameId) {}

    private record ExistingRatingState(
            BigDecimal score,
            String comment,
            String visibility,
            boolean spoiler,
            long watchId,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {}

    private record ExistingWatchState(String status, java.time.Instant ratedAt) {}

    private record ExistingFrameState(long frameId, java.time.Instant createdAt) {}
}
