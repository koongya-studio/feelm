package com.feelm.backend.domain.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.notification.service.NotificationCommandService;
import com.feelm.backend.domain.watch.repository.WatchRepository;
import com.feelm.backend.domain.watch.service.WatchService;
import com.feelm.backend.domain.watch.service.WatchService.OttClickResult;
import com.feelm.backend.global.security.JjwtTokenProvider;
import com.feelm.backend.global.security.JwtProperties;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@DisplayName("OTT 클릭 API 계약 — API-033")
class WatchApiContractTest extends SharedPostgresContractTest {

    private static final long DAY = 86_400;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private JdbcClient jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private WatchRepository watchRepository;
    @Autowired private KafkaEventPublisher eventPublisher;
    @Autowired private NotificationCommandService notificationCommandService;
    @Autowired private PlatformTransactionManager transactionManager;
    private long userId;
    private long withdrawnUserId;
    private long otherUserId;
    private long movieId;
    private long otherMovieId;
    private long ottId;
    private long otherOttId;

    @BeforeEach
    void setUp() {
        jdbc.sql(
                        "TRUNCATE TABLE outbox_events, ratings, watch_records,"
                                + " movie_ott_providers, movies, otts, users"
                                + " RESTART IDENTITY CASCADE")
                .update();
        userId = insertUser("watcher@feelm.test", "감상자", "ACTIVE");
        withdrawnUserId = insertUser("withdrawn@feelm.test", "탈퇴자", "WITHDRAWN");
        otherUserId = insertUser("other@feelm.test", "다른감상자", "ACTIVE");
        movieId = insertMovie(990_001, "감상 테스트 영화");
        otherMovieId = insertMovie(990_002, "다른 감상 테스트 영화");
        ottId = insertOtt("NETFLIX", 8);
        otherOttId = insertOtt("WATCHA", 97);
        link(movieId, ottId);
        link(movieId, otherOttId);
        link(otherMovieId, ottId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_ott_click_outbox ON outbox_events").update();
        jdbc.sql("DROP FUNCTION IF EXISTS test_fail_ott_click_outbox()").update();
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_watch_insert ON watch_records").update();
        jdbc.sql("DROP FUNCTION IF EXISTS test_fail_watch_insert()").update();
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_watch_notification ON notifications").update();
        jdbc.sql("DROP FUNCTION IF EXISTS test_fail_watch_notification()").update();
        jdbc.sql(
                        "TRUNCATE TABLE outbox_events, ratings, watch_records,"
                                + " movie_ott_providers, movies, otts, users"
                                + " RESTART IDENTITY CASCADE")
                .update();
    }

    @Test
    @DisplayName("최초 클릭은 LINK_CLICKED 기록과 PENDING OTT_CLICK 이벤트를 원자적으로 만든다")
    void firstClickCreatesWatchRecordAndPendingOutbox() throws Exception {
        Instant before = Instant.now();
        JsonNode response = click(movieId, ottId, userId, 201);
        Instant after = Instant.now();

        long watchRecordId = response.path("watchRecordId").asLong();
        Instant clickedAt = Instant.parse(response.path("clickedAt").asText());
        assertThat(response.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "watchRecordId",
                        "movieId",
                        "status",
                        "clickedAt",
                        "confirmDueAt",
                        "expiresAt");
        assertThat(response.path("movieId").asLong()).isEqualTo(movieId);
        assertThat(response.path("status").asText()).isEqualTo("LINK_CLICKED");
        assertThat(clickedAt).isBetween(before, after);
        assertThat(response.path("confirmDueAt").asText())
                .isEqualTo(clickedAt.plusSeconds(2 * DAY).toString());
        assertThat(response.path("expiresAt").asText())
                .isEqualTo(clickedAt.plusSeconds(7 * DAY).toString());

        WatchRow stored = watch(watchRecordId);
        assertThat(stored.userId()).isEqualTo(userId);
        assertThat(stored.movieId()).isEqualTo(movieId);
        assertThat(stored.ottId()).isEqualTo(ottId);
        assertThat(stored.status()).isEqualTo("LINK_CLICKED");
        assertThat(stored.clickedAt()).isEqualTo(clickedAt);
        assertThat(stored.confirmDueAt()).isEqualTo(clickedAt.plusSeconds(2 * DAY));
        assertThat(stored.expiresAt()).isEqualTo(clickedAt.plusSeconds(7 * DAY));
        assertThat(activeRows(userId, movieId)).isEqualTo(1);
        assertEvent(onlyEvent(), userId, movieId, ottId, watchRecordId);
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM notifications"
                                                + " WHERE user_id=:user AND type='WATCH_CONFIRM'"
                                                + " AND source_id=:record AND target_id=:record"
                                                + " AND notified_at=:due AND expires_at=:expires")
                                .param("user", userId)
                                .param("record", watchRecordId)
                                .param("due", offset(stored.confirmDueAt()))
                                .param("expires", offset(stored.expiresAt()))
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("알림 저장 실패 시 감상 기록과 아웃박스를 함께 롤백한다")
    void rollsBackWatchAndOutboxWhenNotificationWriteFails() throws Exception {
        jdbc.sql(
                        """
                        CREATE FUNCTION test_fail_watch_notification() RETURNS trigger
                        LANGUAGE plpgsql AS $$
                        BEGIN
                            RAISE EXCEPTION 'forced notification failure';
                        END;
                        $$
                        """)
                .update();
        jdbc.sql(
                        """
                        CREATE TRIGGER test_fail_watch_notification
                        BEFORE INSERT ON notifications
                        FOR EACH ROW EXECUTE FUNCTION test_fail_watch_notification()
                        """)
                .update();

        mockMvc.perform(
                        post(path(movieId))
                                .header("Authorization", bearer(userId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ottId\":" + ottId + "}"))
                .andExpect(status().is5xxServerError());

        assertThat(watchRows(userId, movieId)).isZero();
        assertThat(events()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM notifications").query(Long.class).single())
                .isZero();
    }

    @Test
    @DisplayName("재클릭은 같은 기록의 OTT와 세 시각을 갱신하고 호출마다 이벤트를 남긴다")
    void reclickRefreshesSameRecordAndUsesRequestedOtt() throws Exception {
        long watchRecordId = click(movieId, ottId, userId, 201).path("watchRecordId").asLong();
        Instant old = Instant.now().minusSeconds(DAY);
        jdbc.sql(
                        "UPDATE watch_records SET clicked_at=:old, confirm_due_at=:old,"
                                + " expires_at=:future WHERE id=:id")
                .param("old", offset(old))
                .param("future", offset(Instant.now().plusSeconds(DAY)))
                .param("id", watchRecordId)
                .update();
        jdbc.sql("UPDATE notifications SET read_at=now() WHERE source_id=:record")
                .param("record", watchRecordId)
                .update();

        JsonNode response = click(movieId, otherOttId, userId, 200);

        assertThat(response.path("watchRecordId").asLong()).isEqualTo(watchRecordId);
        Instant refreshedAt = Instant.parse(response.path("clickedAt").asText());
        WatchRow stored = watch(watchRecordId);
        assertThat(stored.ottId()).isEqualTo(otherOttId);
        assertThat(stored.clickedAt()).isEqualTo(refreshedAt);
        assertThat(stored.confirmDueAt()).isEqualTo(refreshedAt.plusSeconds(2 * DAY));
        assertThat(stored.expiresAt()).isEqualTo(refreshedAt.plusSeconds(7 * DAY));
        assertThat(watchRows(userId, movieId)).isEqualTo(1);
        List<EventRow> events = events();
        assertThat(events).hasSize(2);
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM notifications"
                                                + " WHERE type='WATCH_CONFIRM' AND source_id=:record")
                                .param("record", watchRecordId)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM notifications"
                                                + " WHERE source_id=:record AND read_at IS NULL"
                                                + " AND notified_at=:due AND expires_at=:expires")
                                .param("record", watchRecordId)
                                .param("due", offset(stored.confirmDueAt()))
                                .param("expires", offset(stored.expiresAt()))
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        assertThat(events).extracting(EventRow::eventId).doesNotHaveDuplicates();
        assertEvent(events.get(0), userId, movieId, ottId, watchRecordId);
        assertEvent(events.get(1), userId, movieId, otherOttId, watchRecordId);
    }

    @Test
    @DisplayName("EXPIRED 이력만 있으면 새 활성 기록과 새 기록 ID의 이벤트를 만든다")
    void clickAfterStoredExpiredHistoryCreatesNewRecord() throws Exception {
        long expiredId =
                insertWatch(movieId, userId, ottId, "EXPIRED", Instant.now().minusSeconds(8 * DAY));

        JsonNode response = click(movieId, ottId, userId, 201);

        long newId = response.path("watchRecordId").asLong();
        assertThat(newId).isNotEqualTo(expiredId);
        assertThat(watchRows(userId, movieId)).isEqualTo(2);
        assertThat(activeRows(userId, movieId)).isEqualTo(1);
        assertThat(watch(expiredId).status()).isEqualTo("EXPIRED");
        assertEvent(onlyEvent(), userId, movieId, ottId, newId);
    }

    @Test
    @DisplayName("만료 경계에서는 대상 영화만 만료시키고 새 기록을 만든다")
    void expirationBoundaryExpiresOnlyTargetMovie() throws Exception {
        long dueTarget =
                insertWatch(
                        movieId,
                        userId,
                        ottId,
                        "LINK_CLICKED",
                        Instant.now().minusSeconds(8 * DAY));
        long unrelatedDue =
                insertWatch(
                        otherMovieId,
                        userId,
                        ottId,
                        "LINK_CLICKED",
                        Instant.now().minusSeconds(8 * DAY));

        JsonNode response = click(movieId, ottId, userId, 201);

        long replacement = response.path("watchRecordId").asLong();
        assertThat(watch(dueTarget).status()).isEqualTo("EXPIRED");
        assertThat(watch(unrelatedDue).status()).isEqualTo("LINK_CLICKED");
        assertThat(activeRows(userId, movieId)).isEqualTo(1);
        assertEvent(onlyEvent(), userId, movieId, ottId, replacement);
    }

    @Test
    @DisplayName("PostgreSQL 만료 시각이 고정 now와 같으면 기존 행을 만료하고 새 행을 만든다")
    void exactPostgresExpirationBoundaryCreatesReplacement() {
        Instant now = Instant.parse("2026-09-15T01:23:45.123456Z");
        long expired =
                insertWatch(movieId, userId, ottId, "LINK_CLICKED", now.minusSeconds(7 * DAY));
        assertThat(watch(expired).expiresAt()).isEqualTo(now);

        OttClickResult result = clickWithFixedClock(now, ottId);

        assertThat(result.created()).isTrue();
        long replacement = result.response().watchRecordId();
        assertThat(replacement).isNotEqualTo(expired);
        assertThat(result.response().clickedAt()).isEqualTo(now);
        assertThat(watch(expired).status()).isEqualTo("EXPIRED");
        assertThat(watch(expired).expiredAt()).isEqualTo(now);
        assertThat(watch(replacement).status()).isEqualTo("LINK_CLICKED");
        assertThat(watchRows(userId, movieId)).isEqualTo(2);
        assertThat(activeRows(userId, movieId)).isEqualTo(1);
        assertEvent(onlyEvent(), userId, movieId, ottId, replacement);
    }

    @Test
    @DisplayName("PostgreSQL 만료 시각이 고정 now보다 1마이크로초 뒤면 같은 행을 갱신한다")
    void oneMicrosecondBeforePostgresExpirationRefreshesSameRecord() {
        Instant now = Instant.parse("2026-09-15T01:23:45.123456Z");
        Instant expiresAt = now.plusNanos(1_000);
        long current =
                insertWatch(
                        movieId, userId, ottId, "LINK_CLICKED", expiresAt.minusSeconds(7 * DAY));
        assertThat(watch(current).expiresAt()).isEqualTo(expiresAt);

        OttClickResult result = clickWithFixedClock(now, otherOttId);

        assertThat(result.created()).isFalse();
        assertThat(result.response().watchRecordId()).isEqualTo(current);
        WatchRow refreshed = watch(current);
        assertThat(refreshed.ottId()).isEqualTo(otherOttId);
        assertThat(refreshed.status()).isEqualTo("LINK_CLICKED");
        assertThat(refreshed.clickedAt()).isEqualTo(now);
        assertThat(refreshed.expiresAt()).isEqualTo(now.plusSeconds(7 * DAY));
        assertThat(watchRows(userId, movieId)).isEqualTo(1);
        assertThat(activeRows(userId, movieId)).isEqualTo(1);
        assertEvent(onlyEvent(), userId, movieId, otherOttId, current);
    }

    @ParameterizedTest(name = "완료 상태 {0}")
    @ValueSource(strings = {"WATCHED_UNRATED", "RATED"})
    @DisplayName("감상 완료 상태는 모든 저장 정보를 보존하면서 클릭 이벤트만 추가한다")
    void completedRecordsKeepStateAndStillEmitClick(String completedStatus) throws Exception {
        long watchRecordId =
                insertWatch(
                        movieId, userId, ottId, completedStatus, Instant.now().minusSeconds(DAY));
        if (completedStatus.equals("RATED")) {
            insertRating(watchRecordId, userId, movieId);
        }
        WatchRow before = watch(watchRecordId);
        long ratingCountBefore = count("ratings");

        JsonNode response = click(movieId, otherOttId, userId, 200);

        assertThat(response.path("watchRecordId").asLong()).isEqualTo(watchRecordId);
        assertThat(response.path("status").asText()).isEqualTo(completedStatus);
        assertThat(watch(watchRecordId)).isEqualTo(before);
        assertThat(count("ratings")).isEqualTo(ratingCountBefore);
        assertEvent(onlyEvent(), userId, movieId, otherOttId, watchRecordId);
    }

    @Test
    @DisplayName("클릭 이력 없이 평가한 RATED 기록은 nullable 시각과 평가 연결을 보존한다")
    void ratedWithoutClickKeepsNullTimesAndRating() throws Exception {
        long watchRecordId = insertWatch(movieId, userId, null, "RATED", null);
        long ratingId = insertRating(watchRecordId, userId, movieId);

        JsonNode response = click(movieId, ottId, userId, 200);

        assertThat(response.has("clickedAt")).isTrue();
        assertThat(response.has("confirmDueAt")).isTrue();
        assertThat(response.has("expiresAt")).isTrue();
        assertThat(response.path("clickedAt").isNull()).isTrue();
        assertThat(response.path("confirmDueAt").isNull()).isTrue();
        assertThat(response.path("expiresAt").isNull()).isTrue();
        assertThat(watch(watchRecordId).ottId()).isNull();
        assertThat(
                        jdbc.sql("SELECT watch_record_id FROM ratings WHERE id=:id")
                                .param("id", ratingId)
                                .query(Long.class)
                                .single())
                .isEqualTo(watchRecordId);
        assertEvent(onlyEvent(), userId, movieId, ottId, watchRecordId);
    }

    @Test
    @DisplayName("없는 영화·없는 OTT·미연결 승인 OTT는 404이며 어떤 쓰기도 없다")
    void missingOrUnlinkedResourceReturnsNotFoundAtomically() throws Exception {
        assertNotFound(movieId + 99_999, ottId);
        assertNotFound(movieId, ottId + 99_999);
        for (int index = 0; index < 5; index++) {
            long unlinked = insertOtt("UNLINKED_" + index, 500 + index);
            assertNotFound(movieId, unlinked);
        }

        assertThat(count("watch_records")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    @DisplayName("movieId와 ottId는 양의 64비트 정수만 허용하고 실패 시 이벤트를 남기지 않는다")
    void invalidIdentifiersAndBodyReturnValidationError() throws Exception {
        for (String invalidMovie : List.of("0", "-1", "not-a-number", "9223372036854775808")) {
            assertJsonError(
                    mockMvc.perform(
                            post("/api/v1/movies/" + invalidMovie + "/ott-clicks")
                                    .header("Authorization", bearer(userId))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"ottId\":" + ottId + "}")),
                    400,
                    "VALIDATION_FAILED",
                    "요청 값이 올바르지 않습니다.");
            assertNoClickWrites();
        }
        for (String invalidBody :
                List.of(
                        "{}",
                        "{\"ottId\":null}",
                        "{\"ottId\":0}",
                        "{\"ottId\":-1}",
                        "{\"ottId\":\"1\"}",
                        "{\"ottId\":1.5}",
                        "{\"ottId\":9223372036854775808}",
                        "{\"ottId\":true}",
                        "{\"ottId\":[]}",
                        "{\"ottId\":{}}")) {
            assertJsonError(
                    mockMvc.perform(
                            post(path(movieId))
                                    .header("Authorization", bearer(userId))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(invalidBody)),
                    400,
                    "VALIDATION_FAILED",
                    "요청 값이 올바르지 않습니다.");
            assertNoClickWrites();
        }
    }

    @Test
    @DisplayName("빈 본문과 깨진 JSON은 공통 검증 오류이며 어떤 쓰기도 없다")
    void unreadableBodiesReturnValidationErrorWithoutWrites() throws Exception {
        for (String unreadableBody : List.of("", "{\"ottId\":")) {
            assertJsonError(
                    mockMvc.perform(
                            post(path(movieId))
                                    .header("Authorization", bearer(userId))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(unreadableBody)),
                    400,
                    "VALIDATION_FAILED",
                    "요청 값이 올바르지 않습니다.");
            assertNoClickWrites();
        }
    }

    @Test
    @DisplayName("JSON 본문에 Content-Type이 없으면 공통 415 오류이며 어떤 쓰기도 없다")
    void missingContentTypeReturnsUnsupportedMediaTypeWithoutWrites() throws Exception {
        assertJsonError(
                mockMvc.perform(
                        post(path(movieId))
                                .header("Authorization", bearer(userId))
                                .content("{\"ottId\":" + ottId + "}")),
                415,
                "UNSUPPORTED_MEDIA_TYPE",
                "지원하지 않는 Content-Type입니다.");
        assertNoClickWrites();
    }

    @Test
    @DisplayName("Access Token이 없거나 잘못됐거나 만료되면 클릭을 기록하지 않는다")
    void requiresValidBearerAccessToken() throws Exception {
        mockMvc.perform(
                        post(path(movieId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ottId\":" + ottId + "}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(
                        post(path(movieId))
                                .header("Authorization", "Bearer invalid-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ottId\":" + ottId + "}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(
                        post(path(movieId))
                                .header("Authorization", "Bearer " + expiredAccessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ottId\":" + ottId + "}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));

        assertThat(count("watch_records")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    @DisplayName("탈퇴 사용자는 유효한 토큰이 있어도 클릭을 기록할 수 없다")
    void withdrawnUserCannotRecordClick() throws Exception {
        request(movieId, ottId, withdrawnUserId)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(count("watch_records")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    @DisplayName("아웃박스 저장 실패 시 최초 클릭 생성을 롤백한다")
    void outboxFailureRollsBackNewClick() throws Exception {
        failOutbox();

        request(movieId, ottId, userId)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(count("watch_records")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    @DisplayName("아웃박스 저장 실패 시 LINK_CLICKED 재클릭 갱신을 롤백한다")
    void outboxFailureRollsBackRefresh() throws Exception {
        long watchRecordId =
                insertWatch(
                        movieId, userId, ottId, "LINK_CLICKED", Instant.now().minusSeconds(DAY));
        WatchRow before = watch(watchRecordId);
        failOutbox();

        request(movieId, otherOttId, userId)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(watch(watchRecordId)).isEqualTo(before);
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    @DisplayName("아웃박스 저장 실패 시 만료 전이와 대체 기록 생성을 함께 롤백한다")
    void outboxFailureRollsBackExpiration() throws Exception {
        long watchRecordId =
                insertWatch(
                        movieId,
                        userId,
                        ottId,
                        "LINK_CLICKED",
                        Instant.now().minusSeconds(8 * DAY));
        WatchRow before = watch(watchRecordId);
        failOutbox();

        request(movieId, ottId, userId)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(watchRows(userId, movieId)).isEqualTo(1);
        assertThat(watch(watchRecordId)).isEqualTo(before);
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    @DisplayName("감상 기록 저장 실패 시 아웃박스를 만들지 않는다")
    void watchRecordFailureCreatesNoOutbox() throws Exception {
        failWatchInsert();

        request(movieId, ottId, userId)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(count("watch_records")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    @DisplayName("동일 사용자·영화·서로 다른 OTT 동시 최초 클릭은 실제 잠금 대기 후 직렬화된다")
    void concurrentFirstClicksKeepOneActiveRecord() throws Exception {
        LockedConcurrentResult concurrent =
                concurrentClicksWithObservedUserLockWait(movieId, ottId, movieId, otherOttId);
        List<ConcurrentResponse> responses = concurrent.responses();

        assertObservedLockWait(concurrent);
        assertThat(responses)
                .extracting(ConcurrentResponse::status)
                .containsExactlyInAnyOrder(201, 200);
        long activeId = responses.getFirst().body().path("watchRecordId").asLong();
        assertThat(responses)
                .extracting(response -> response.body().path("watchRecordId").asLong())
                .containsOnly(activeId);
        assertThat(activeRows(userId, movieId)).isEqualTo(1);
        assertThat(watchRows(userId, movieId)).isEqualTo(1);
        assertThat(watch(activeId).ottId()).isIn(ottId, otherOttId);
        List<EventRow> events = events();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(EventRow::eventId).doesNotHaveDuplicates();
        assertThat(events)
                .extracting(this::eventOttId)
                .containsExactlyInAnyOrder(ottId, otherOttId);
        events.forEach(event -> assertEvent(event, userId, movieId, eventOttId(event), activeId));
    }

    @Test
    @DisplayName("만료 경계의 동시 클릭 두 건은 대체 활성 기록 하나와 이벤트 두 건을 남긴다")
    void concurrentClicksAtExpirationKeepOneReplacement() throws Exception {
        long expired =
                insertWatch(
                        movieId,
                        userId,
                        ottId,
                        "LINK_CLICKED",
                        Instant.now().minusSeconds(8 * DAY));

        LockedConcurrentResult concurrent =
                concurrentClicksWithObservedUserLockWait(movieId, ottId, movieId, ottId);
        List<ConcurrentResponse> responses = concurrent.responses();

        assertObservedLockWait(concurrent);
        assertThat(responses)
                .extracting(ConcurrentResponse::status)
                .containsExactlyInAnyOrder(201, 200);
        long replacement = responses.getFirst().body().path("watchRecordId").asLong();
        assertThat(replacement).isNotEqualTo(expired);
        assertThat(responses)
                .extracting(response -> response.body().path("watchRecordId").asLong())
                .containsOnly(replacement);
        assertThat(watch(expired).status()).isEqualTo("EXPIRED");
        assertThat(watchRows(userId, movieId)).isEqualTo(2);
        assertThat(activeRows(userId, movieId)).isEqualTo(1);
        List<EventRow> events = events();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(EventRow::eventId).doesNotHaveDuplicates();
        assertThat(events)
                .allSatisfy(event -> assertEvent(event, userId, movieId, ottId, replacement));
    }

    @Test
    @DisplayName("서로 다른 사용자의 서로 다른 영화 클릭은 전역 잠금 없이 함께 진행된다")
    void differentUsersAndMoviesAreNotGloballySerialized() throws Exception {
        List<ConcurrentResponse> responses =
                concurrentClicks(userId, movieId, ottId, otherUserId, otherMovieId, ottId);

        assertThat(responses).extracting(ConcurrentResponse::status).containsOnly(201);
        assertThat(activeRows(userId, movieId)).isEqualTo(1);
        assertThat(activeRows(otherUserId, otherMovieId)).isEqualTo(1);
        assertThat(events()).hasSize(2);
    }

    private void failOutbox() {
        jdbc.sql(
                        """
                        CREATE OR REPLACE FUNCTION test_fail_ott_click_outbox()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            IF NEW.event_type = 'OTT_CLICK' THEN
                                RAISE EXCEPTION 'forced OTT_CLICK outbox failure';
                            END IF;
                            RETURN NEW;
                        END;
                        $$
                        """)
                .update();
        jdbc.sql(
                        """
                        CREATE TRIGGER test_fail_ott_click_outbox
                        BEFORE INSERT ON outbox_events
                        FOR EACH ROW EXECUTE FUNCTION test_fail_ott_click_outbox()
                        """)
                .update();
    }

    private void failWatchInsert() {
        jdbc.sql(
                        """
                        CREATE OR REPLACE FUNCTION test_fail_watch_insert()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            RAISE EXCEPTION 'forced watch record failure';
                        END;
                        $$
                        """)
                .update();
        jdbc.sql(
                        """
                        CREATE TRIGGER test_fail_watch_insert
                        BEFORE INSERT ON watch_records
                        FOR EACH ROW EXECUTE FUNCTION test_fail_watch_insert()
                        """)
                .update();
    }

    private List<ConcurrentResponse> concurrentClicks(
            long firstUser,
            long firstMovie,
            long firstOtt,
            long secondUser,
            long secondMovie,
            long secondOtt)
            throws Exception {
        ExecutorService concurrentExecutor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<ConcurrentResponse> first =
                concurrentExecutor.submit(
                        () -> concurrentClick(firstUser, firstMovie, firstOtt, ready, start));
        Future<ConcurrentResponse> second =
                concurrentExecutor.submit(
                        () -> concurrentClick(secondUser, secondMovie, secondOtt, ready, start));
        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            stopExecutor(concurrentExecutor, first, second);
        }
    }

    private LockedConcurrentResult concurrentClicksWithObservedUserLockWait(
            long firstMovie, long firstOtt, long secondMovie, long secondOtt) throws Exception {
        ExecutorService concurrentExecutor = Executors.newFixedThreadPool(2);
        Future<ConcurrentResponse> first = null;
        Future<ConcurrentResponse> second = null;
        try (Connection lockConnection = dataSource.getConnection();
                Connection observerConnection = dataSource.getConnection()) {
            lockConnection.setAutoCommit(false);
            boolean lockReleased = false;
            try {
                int blockerPid = backendPid(lockConnection);
                lockUser(lockConnection, userId);

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                first =
                        concurrentExecutor.submit(
                                () -> concurrentClick(userId, firstMovie, firstOtt, ready, start));
                second =
                        concurrentExecutor.submit(
                                () ->
                                        concurrentClick(
                                                userId, secondMovie, secondOtt, ready, start));
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                List<LockWaitObservation> waits =
                        awaitUserLockWaits(
                                observerConnection, blockerPid, 2, Duration.ofSeconds(10));
                assertThat(first.isDone()).as("첫 요청은 제어 잠금 해제 전에 끝나지 않아야 한다").isFalse();
                assertThat(second.isDone()).as("둘째 요청은 제어 잠금 해제 전에 끝나지 않아야 한다").isFalse();

                lockConnection.commit();
                lockReleased = true;
                return new LockedConcurrentResult(
                        List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)),
                        blockerPid,
                        waits);
            } finally {
                if (!lockReleased) {
                    lockConnection.rollback();
                }
            }
        } finally {
            stopExecutor(concurrentExecutor, first, second);
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static void lockUser(Connection connection, long lockedUserId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT id FROM users WHERE id=? FOR NO KEY UPDATE")) {
            statement.setLong(1, lockedUserId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(lockedUserId);
            }
        }
    }

    private static List<LockWaitObservation> awaitUserLockWaits(
            Connection observer, int blockerPid, int expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<LockWaitObservation> latest = List.of();
        while (System.nanoTime() < deadline) {
            latest = userLockWaits(observer);
            if (latest.size() >= expected) {
                return latest;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError(
                "Expected "
                        + expected
                        + " PostgreSQL user-row lock waiters for blocker pid "
                        + blockerPid
                        + ", observed "
                        + latest
                        + "; activity="
                        + activitySnapshot(observer));
    }

    private static List<LockWaitObservation> userLockWaits(Connection observer)
            throws SQLException {
        String sql =
                """
                SELECT pid, wait_event_type, wait_event, state, query,
                       pg_blocking_pids(pid) AS blocking_pids
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND wait_event_type = 'Lock'
                  AND query ILIKE '%FROM users%'
                  AND query ILIKE '%FOR NO KEY UPDATE%'
                ORDER BY pid
                """;
        List<LockWaitObservation> waits = new ArrayList<>();
        try (Statement statement = observer.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                waits.add(
                        new LockWaitObservation(
                                result.getInt("pid"),
                                result.getString("wait_event_type"),
                                result.getString("wait_event"),
                                result.getString("state"),
                                result.getString("query"),
                                blockingPids(result)));
            }
        }
        return waits;
    }

    private static List<Integer> blockingPids(ResultSet result) throws SQLException {
        Array array = result.getArray("blocking_pids");
        if (array == null) {
            return List.of();
        }
        try {
            return Arrays.stream((Object[]) array.getArray())
                    .map(value -> ((Number) value).intValue())
                    .toList();
        } finally {
            array.free();
        }
    }

    private static String activitySnapshot(Connection observer) throws SQLException {
        String sql =
                """
                SELECT pid, state, COALESCE(wait_event_type, '-'), COALESCE(wait_event, '-'),
                       pg_blocking_pids(pid)::text, query
                FROM pg_stat_activity
                WHERE datname = current_database() AND pid <> pg_backend_pid()
                ORDER BY pid
                """;
        List<String> activity = new ArrayList<>();
        try (Statement statement = observer.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                activity.add(
                        "pid="
                                + result.getInt(1)
                                + ", state="
                                + result.getString(2)
                                + ", waitType="
                                + result.getString(3)
                                + ", wait="
                                + result.getString(4)
                                + ", blockers="
                                + result.getString(5)
                                + ", query="
                                + result.getString(6));
            }
        }
        return activity.toString();
    }

    private static void assertObservedLockWait(LockedConcurrentResult concurrent) {
        assertThat(concurrent.waits()).hasSize(2);
        assertThat(concurrent.waits())
                .allSatisfy(
                        wait -> {
                            assertThat(wait.waitEventType()).isEqualTo("Lock");
                            assertThat(wait.state()).isEqualTo("active");
                            assertThat(wait.query())
                                    .containsIgnoringCase("FROM users")
                                    .containsIgnoringCase("FOR NO KEY UPDATE");
                            assertThat(wait.blockingPids()).isNotEmpty();
                        });
        assertThat(concurrent.waits())
                .anySatisfy(
                        wait -> assertThat(wait.blockingPids()).contains(concurrent.blockerPid()));
    }

    private static void stopExecutor(
            ExecutorService concurrentExecutor, Future<?> first, Future<?> second)
            throws InterruptedException {
        for (Future<?> future : new Future<?>[] {first, second}) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
        concurrentExecutor.shutdownNow();
        assertThat(concurrentExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private ConcurrentResponse concurrentClick(
            long user, long movie, long ott, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        MvcResult result =
                mockMvc.perform(
                                post(path(movie))
                                        .header("Authorization", bearer(user))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"ottId\":" + ott + "}"))
                        .andReturn();
        JsonNode body =
                objectMapper.readTree(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return new ConcurrentResponse(result.getResponse().getStatus(), body);
    }

    private JsonNode click(long movie, long ott, long user, int expectedStatus) throws Exception {
        MvcResult result =
                request(movie, ott, user)
                        .andExpect(status().is(expectedStatus))
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andReturn();
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions request(long movie, long ott, long user) throws Exception {
        return mockMvc.perform(
                post(path(movie))
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ottId\":" + ott + "}"));
    }

    private OttClickResult clickWithFixedClock(Instant now, long requestedOttId) {
        WatchService service =
                new WatchService(
                        watchRepository,
                        eventPublisher,
                        notificationCommandService,
                        Clock.fixed(now, ZoneOffset.UTC));
        OttClickResult result =
                new TransactionTemplate(transactionManager)
                        .execute(status -> service.clickOtt(userId, movieId, requestedOttId));
        assertThat(result).isNotNull();
        return result;
    }

    private void assertJsonError(
            ResultActions actions, int expectedStatus, String expectedCode, String expectedMessage)
            throws Exception {
        MvcResult result =
                actions.andExpect(status().is(expectedStatus))
                        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.code").value(expectedCode))
                        .andExpect(jsonPath("$.message").value(expectedMessage))
                        .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .doesNotContain(
                        "Exception", "SQLException", "org.springframework", "java.", "SELECT ");
    }

    private void assertNoClickWrites() {
        assertThat(count("watch_records")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    private void assertNotFound(long movie, long ott) throws Exception {
        request(movie, ott, userId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private void assertEvent(EventRow event, long user, long movie, long ott, long watchRecordId) {
        assertThatCode(() -> UUID.fromString(event.eventId())).doesNotThrowAnyException();
        assertThat(event.eventType()).isEqualTo("OTT_CLICK");
        assertThat(event.topic()).isEqualTo("feelm.ott-click.v1");
        assertThat(event.partitionKey()).isEqualTo(user);
        assertThat(event.status()).isEqualTo("PENDING");
        try {
            JsonNode envelope = objectMapper.readTree(event.message());
            assertThat(envelope.path("eventId").asText()).isEqualTo(event.eventId());
            assertThat(envelope.path("eventType").asText()).isEqualTo("OTT_CLICK");
            assertThat(envelope.path("userId").asLong()).isEqualTo(user);
            assertThat(OffsetDateTime.parse(envelope.path("occurredAt").asText()).getOffset())
                    .isEqualTo(ZoneOffset.UTC);
            JsonNode payload = envelope.path("payload");
            assertThat(payload.properties())
                    .extracting(Map.Entry::getKey)
                    .containsExactlyInAnyOrder("movieId", "ottId", "watchRecordId");
            assertThat(payload.path("movieId").asLong()).isEqualTo(movie);
            assertThat(payload.path("ottId").asLong()).isEqualTo(ott);
            assertThat(payload.path("watchRecordId").asLong()).isEqualTo(watchRecordId);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private EventRow onlyEvent() {
        List<EventRow> stored = events();
        assertThat(stored).hasSize(1);
        return stored.getFirst();
    }

    private List<EventRow> events() {
        return jdbc.sql(
                        """
                        SELECT event_id::text, event_type, topic, partition_key,
                               status, message::text
                        FROM outbox_events
                        WHERE event_type='OTT_CLICK'
                        ORDER BY id
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
                .list();
    }

    private long eventOttId(EventRow event) {
        try {
            return objectMapper.readTree(event.message()).path("payload").path("ottId").asLong();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private WatchRow watch(long id) {
        return jdbc.sql(
                        """
                        SELECT id, user_id, movie_id, ott_id, status, clicked_at,
                               confirm_due_at, expires_at, watched_at, rated_at, expired_at
                        FROM watch_records WHERE id=:id
                        """)
                .param("id", id)
                .query(
                        (row, number) ->
                                new WatchRow(
                                        row.getLong("id"),
                                        row.getLong("user_id"),
                                        row.getLong("movie_id"),
                                        nullableLong(row, "ott_id"),
                                        row.getString("status"),
                                        instant(row, "clicked_at"),
                                        instant(row, "confirm_due_at"),
                                        instant(row, "expires_at"),
                                        instant(row, "watched_at"),
                                        instant(row, "rated_at"),
                                        instant(row, "expired_at")))
                .single();
    }

    private long insertUser(String email, String nickname, String userStatus) {
        return jdbc.sql(
                        """
                        INSERT INTO users(email, nickname, birth_date, gender, status)
                        VALUES (:email, :nickname, DATE '2000-01-01', 'OTHER', :status)
                        RETURNING id
                        """)
                .param("email", email)
                .param("nickname", nickname)
                .param("status", userStatus)
                .query(Long.class)
                .single();
    }

    private long insertMovie(int tmdbId, String title) {
        return jdbc.sql(
                        "INSERT INTO movies(tmdb_id,title,poster_url)"
                                + " VALUES (:tmdbId,:title,:poster) RETURNING id")
                .param("tmdbId", tmdbId)
                .param("title", title)
                .param("poster", "https://image.tmdb.org/t/p/w342/" + tmdbId + ".jpg")
                .query(Long.class)
                .single();
    }

    private long insertOtt(String code, int providerId) {
        return jdbc.sql(
                        "INSERT INTO otts(code,name,tmdb_provider_id)"
                                + " VALUES (:code,:code,:providerId) RETURNING id")
                .param("code", code)
                .param("providerId", providerId)
                .query(Long.class)
                .single();
    }

    private void link(long movie, long ott) {
        jdbc.sql("INSERT INTO movie_ott_providers(movie_id,ott_id) VALUES (:movie,:ott)")
                .param("movie", movie)
                .param("ott", ott)
                .update();
    }

    private long insertWatch(
            long movie, long user, Long ott, String watchStatus, Instant clickedAt) {
        Instant dueAt = clickedAt == null ? null : clickedAt.plusSeconds(2 * DAY);
        Instant expiresAt = clickedAt == null ? null : clickedAt.plusSeconds(7 * DAY);
        Instant watchedAt =
                watchStatus.equals("WATCHED_UNRATED") || watchStatus.equals("RATED")
                        ? Instant.now().minusSeconds(60)
                        : null;
        Instant ratedAt = watchStatus.equals("RATED") ? Instant.now().minusSeconds(30) : null;
        Instant expiredAt = watchStatus.equals("EXPIRED") ? Instant.now().minusSeconds(1) : null;
        return jdbc.sql(
                        """
                        INSERT INTO watch_records(
                            user_id,movie_id,ott_id,status,clicked_at,confirm_due_at,
                            expires_at,watched_at,rated_at,expired_at
                        ) VALUES (
                            :user,:movie,:ott,:status,:clicked,:due,:expires,:watched,:rated,:expired
                        ) RETURNING id
                        """)
                .param("user", user)
                .param("movie", movie)
                .param("ott", ott)
                .param("status", watchStatus)
                .param("clicked", offset(clickedAt))
                .param("due", offset(dueAt))
                .param("expires", offset(expiresAt))
                .param("watched", offset(watchedAt))
                .param("rated", offset(ratedAt))
                .param("expired", offset(expiredAt))
                .query(Long.class)
                .single();
    }

    private long insertRating(long watchRecordId, long user, long movie) {
        return jdbc.sql(
                        """
                        INSERT INTO ratings(user_id,movie_id,watch_record_id,score,comment,visibility)
                        VALUES (:user,:movie,:watchRecordId,4.5,'유지할 평가','PRIVATE')
                        RETURNING id
                        """)
                .param("user", user)
                .param("movie", movie)
                .param("watchRecordId", watchRecordId)
                .query(Long.class)
                .single();
    }

    private long watchRows(long user, long movie) {
        return jdbc.sql(
                        "SELECT count(*) FROM watch_records WHERE user_id=:user AND movie_id=:movie")
                .param("user", user)
                .param("movie", movie)
                .query(Long.class)
                .single();
    }

    private long activeRows(long user, long movie) {
        return jdbc.sql(
                        "SELECT count(*) FROM watch_records WHERE user_id=:user"
                                + " AND movie_id=:movie AND status<>'EXPIRED'")
                .param("user", user)
                .param("movie", movie)
                .query(Long.class)
                .single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private String path(long movie) {
        return "/api/v1/movies/" + movie + "/ott-clicks";
    }

    private String bearer(long user) {
        return "Bearer " + tokenProvider.createAccessToken(user);
    }

    private String expiredAccessToken() {
        JjwtTokenProvider expiring =
                new JjwtTokenProvider(
                        new JwtProperties(
                                jwtProperties.secret(),
                                Duration.ofSeconds(-1),
                                jwtProperties.refreshTokenTtl(),
                                jwtProperties.signupTokenTtl(),
                                jwtProperties.refreshRotationGrace(),
                                jwtProperties.refreshCookie()));
        return expiring.createAccessToken(userId);
    }

    private static Long nullableLong(java.sql.ResultSet row, String column)
            throws java.sql.SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static Instant instant(java.sql.ResultSet row, String column)
            throws java.sql.SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Object offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private record ConcurrentResponse(int status, JsonNode body) {}

    private record LockedConcurrentResult(
            List<ConcurrentResponse> responses, int blockerPid, List<LockWaitObservation> waits) {}

    private record LockWaitObservation(
            int pid,
            String waitEventType,
            String waitEvent,
            String state,
            String query,
            List<Integer> blockingPids) {}

    private record EventRow(
            String eventId,
            String eventType,
            String topic,
            long partitionKey,
            String status,
            String message) {}

    private record WatchRow(
            long id,
            long userId,
            long movieId,
            Long ottId,
            String status,
            Instant clickedAt,
            Instant confirmDueAt,
            Instant expiresAt,
            Instant watchedAt,
            Instant ratedAt,
            Instant expiredAt) {}
}
