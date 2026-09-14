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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * API-024: 실제 마이그레이션과 PostgreSQL을 사용하는 초기 취향 제출 HTTP 계약 테스트.
 *
 * <p>아웃박스 폴링 주기를 길게 잡아 Relay가 검증 대상 행의 상태를 바꾸지 않게 한다. 테스트 환경에는 Broker가 없어 발행은 어차피 실패하지만, 재시도 기록으로
 * 행이 바뀌면 발행 여부 검증이 흔들린다.
 */
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "feelm.kafka.outbox.poll-interval=1h"
        })
@AutoConfigureMockMvc
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

    /** T24에서 비밀번호 변경 트랜잭션을 사전 검사와 계정 행 잠금 사이에 세우기 위한 spy. 기본 동작은 실제 빈과 같다. */
    @MockitoSpyBean PasswordResetTokenIssuer resetTokenIssuer;

    /**
     * 이 컨텍스트에는 Redis가 없어 세션 폐기가 그대로면 비밀번호 변경이 500이 된다. 폐기 자체는 API-012 계약 테스트가 보고, 여기서 보는 것은 비밀번호
     * 변경이 계정 행에 남기는 결과다. T24에서만 폐기를 비운다.
     */
    @MockitoSpyBean RefreshTokenService refreshTokenService;

    private ExecutorService executor;

    @BeforeEach
    void fixtures() {
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

    @Test
    @DisplayName("T10 선택을 저장하고 온보딩을 완료 처리한다")
    void savesPreferencesAndCompletesOnboarding() throws Exception {
        submit(ACTIVE, bothChoices())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingCompleted").value(true))
                .andExpect(jsonPath("$.savedCount").value(2))
                .andExpect(jsonPath("$.skipped").value(false));

        assertThat(preferencesOf(ACTIVE))
                .containsExactly(
                        Map.entry(LIKED_MOVIE, "LIKE"), Map.entry(DISLIKED_MOVIE, "DISLIKE"));
        assertThat(onboardingCompleted(ACTIVE)).isTrue();
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
    void skipSavesNothingAndPublishesNoEvent() throws Exception {
        submit(ACTIVE, "{\"preferences\":[],\"skipped\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingCompleted").value(true))
                .andExpect(jsonPath("$.savedCount").value(0))
                .andExpect(jsonPath("$.skipped").value(true));

        assertThat(preferencesOf(ACTIVE)).isEmpty();
        assertThat(onboardingCompleted(ACTIVE)).isTrue();
        assertThat(outboxEvents()).isEmpty();
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
    void rejectsUnknownMovieId() throws Exception {
        submit(ACTIVE, "{\"preferences\":[" + choice(999, "LIKE") + "],\"skipped\":false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(preferencesOf(ACTIVE)).isEmpty();
        assertThat(onboardingCompleted(ACTIVE)).isFalse();
        assertThat(outboxEvents()).isEmpty();
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
}
