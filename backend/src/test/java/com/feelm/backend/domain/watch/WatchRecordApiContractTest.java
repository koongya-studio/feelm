package com.feelm.backend.domain.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.watch.dto.request.WatchListCriteria;
import com.feelm.backend.domain.watch.dto.response.WatchConfirmResponse;
import com.feelm.backend.domain.watch.dto.response.WatchRecordItemResponse;
import com.feelm.backend.domain.watch.repository.WatchRepository;
import com.feelm.backend.domain.watch.service.WatchService;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.response.CursorPageResponse;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import com.feelm.backend.support.SharedPostgresContractTest;
import com.feelm.backend.support.SharedTestRuntime;
import java.net.URI;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
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
import org.junit.jupiter.params.provider.CsvSource;
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
@SharedTestRuntime
@DisplayName("감상 기록 조회·확인 API 계약 — API-034·035")
class WatchRecordApiContractTest extends SharedPostgresContractTest {

    private static final long DAY = 86_400;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private JdbcClient jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private WatchRepository watchRepository;
    @Autowired private KafkaEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;

    private long userId;
    private long otherUserId;
    private long withdrawnUserId;
    private long movieId;
    private long ottId;

    @BeforeEach
    void setUp() {
        truncate();
        userId = insertUser("record@feelm.test", "기록자", "ACTIVE");
        otherUserId = insertUser("other-record@feelm.test", "다른기록자", "ACTIVE");
        withdrawnUserId = insertUser("withdrawn-record@feelm.test", "탈퇴기록자", "WITHDRAWN");
        movieId = insertMovie(991_001, "감상 기록 영화");
        ottId = insertOtt("NETFLIX", 8);
        link(movieId, ottId);
    }

    @AfterEach
    void cleanUp() {
        for (String trigger : List.of("test_fail_watch_commit", "test_skip_watch_confirm")) {
            jdbc.sql("DROP TRIGGER IF EXISTS " + trigger + " ON watch_records").update();
        }
        for (String function : List.of("test_fail_watch_commit()", "test_skip_watch_confirm()")) {
            jdbc.sql("DROP FUNCTION IF EXISTS " + function).update();
        }
        truncate();
    }

    @Test
    @DisplayName("목록은 영화·OTT·상태·시각·평가 요약을 현재 계약대로 반환한다")
    void listsCompleteOwnedRecordSummary() throws Exception {
        Instant clickedAt = Instant.now().minusSeconds(3 * DAY).truncatedTo(ChronoUnit.MICROS);
        long recordId =
                insertWatch(
                        userId, movieId, ottId, "RATED", clickedAt, clickedAt.plusSeconds(7 * DAY));
        long ratingId = insertRating(recordId, userId, movieId, "그대로 둘 한줄평");
        RatingRow ratingBefore = rating(ratingId);

        JsonNode response = response(list(userId).andExpect(status().isOk()).andReturn());

        assertThat(response.path("items")).hasSize(1);
        JsonNode item = response.path("items").get(0);
        assertThat(item.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "watchRecordId",
                        "movie",
                        "status",
                        "ott",
                        "clickedAt",
                        "confirmDueAt",
                        "expiresAt",
                        "watchedAt",
                        "ratingId");
        assertThat(item.path("watchRecordId").asLong()).isEqualTo(recordId);
        assertThat(item.path("movie").path("movieId").asLong()).isEqualTo(movieId);
        assertThat(item.path("movie").path("title").asText()).isEqualTo("감상 기록 영화");
        assertThat(item.path("movie").path("posterUrl").asText()).contains("991001");
        assertThat(item.path("ott").path("ottId").asLong()).isEqualTo(ottId);
        assertThat(item.path("ott").path("code").asText()).isEqualTo("NETFLIX");
        assertThat(item.path("status").asText()).isEqualTo("RATED");
        assertThat(item.path("clickedAt").asText()).isEqualTo(clickedAt.toString());
        assertThat(item.path("ratingId").asLong()).isEqualTo(ratingId);
        assertThat(response.path("nextCursor").isNull()).isTrue();
        assertThat(response.path("hasNext").asBoolean()).isFalse();
        assertThat(rating(ratingId)).isEqualTo(ratingBefore);
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    @DisplayName("기록이 없거나 다른 사용자 기록뿐이면 빈 목록이다")
    void returnsEmptyListWithoutLeakingOtherUsers() throws Exception {
        insertWatch(
                otherUserId,
                movieId,
                ottId,
                "LINK_CLICKED",
                Instant.now(),
                Instant.now().plusSeconds(7 * DAY));

        JsonNode response = response(list(userId).andExpect(status().isOk()).andReturn());

        assertThat(response.path("items")).isEmpty();
        assertThat(response.path("nextCursor").isNull()).isTrue();
        assertThat(response.path("hasNext").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("사용자나 영화가 다른 잘못 연결된 ratingId를 목록에 노출하지 않는다")
    void doesNotLeakMismatchedRatingAssociations() throws Exception {
        Instant clickedAt = Instant.now().minusSeconds(DAY);
        long recordId =
                insertWatch(
                        userId,
                        movieId,
                        ottId,
                        "WATCHED_UNRATED",
                        clickedAt,
                        clickedAt.plusSeconds(7 * DAY));
        long leakedRatingId = insertRating(recordId, otherUserId, movieId, "노출되면 안 되는 타인 평가");
        long otherMovieId = insertMovie(991_002, "다른 영화 평가");
        long mismatchedMovieRatingId =
                insertRating(recordId, userId, otherMovieId, "노출되면 안 되는 다른 영화 평가");

        JsonNode response = response(list(userId).andExpect(status().isOk()).andReturn());

        assertThat(ids(response)).containsExactly(recordId);
        assertThat(response.path("items").get(0).path("ratingId").isNull()).isTrue();
        assertThat(rating(leakedRatingId).watchRecordId()).isEqualTo(recordId);
        assertThat(rating(mismatchedMovieRatingId).watchRecordId()).isEqualTo(recordId);
    }

    @Test
    @DisplayName("반복 상태는 중복 제거 후 복합 필터로 적용된다")
    void filtersByRepeatedStatuses() throws Exception {
        long linked = insertDistinctWatch("LINK_CLICKED", Instant.now().minusSeconds(DAY));
        long rated = insertDistinctWatch("RATED", Instant.now().minusSeconds(2 * DAY));
        insertDistinctWatch("WATCHED_UNRATED", Instant.now().minusSeconds(3 * DAY));

        JsonNode response =
                response(
                        list(userId, "status", "LINK_CLICKED", "status", "RATED", "status", "RATED")
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(ids(response)).containsExactly(linked, rated);
    }

    @Test
    @DisplayName("confirmDue는 정확한 2일 도달·7일 미도달 LINK_CLICKED만 반환한다")
    void confirmDueUsesExactTwoAndSevenDayBoundaries() {
        Instant now = Instant.parse("2026-09-15T01:23:45.123456Z");
        long dueExactly =
                insertDistinctWatch(
                        "LINK_CLICKED", now.minusSeconds(2 * DAY), now, now.plusSeconds(DAY));
        insertDistinctWatch(
                "LINK_CLICKED",
                now.minusSeconds(2 * DAY),
                now.plusNanos(1_000),
                now.plusSeconds(DAY));
        long notExpired =
                insertDistinctWatch(
                        "LINK_CLICKED",
                        now.minusSeconds(7 * DAY),
                        now.minusSeconds(5 * DAY),
                        now.plusNanos(1_000));
        long expiresExactly =
                insertDistinctWatch(
                        "LINK_CLICKED", now.minusSeconds(7 * DAY), now.minusSeconds(5 * DAY), now);
        insertDistinctWatch("RATED", now.minusSeconds(DAY));

        CursorPageResponse<WatchRecordItemResponse> response =
                listWithFixedClock(now, WatchListCriteria.from(null, "true", null, "50"));

        assertThat(response.items())
                .extracting(WatchRecordItemResponse::watchRecordId)
                .containsExactly(dueExactly, notExpired);
        assertThat(
                        listWithFixedClock(
                                        now,
                                        WatchListCriteria.from(
                                                List.of("RATED"), "true", null, "50"))
                                .items())
                .isEmpty();
        WatchRow expired = watch(expiresExactly);
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThat(expired.expiredAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("조회는 expiresAt == now인 본인 기록만 EXPIRED로 저장하고 결과에 반영한다")
    void listingPersistsOnlyOwnedExpirationAtBoundary() {
        Instant now = Instant.parse("2026-09-15T01:23:45.123456Z");
        long owned =
                insertDistinctWatch(
                        "LINK_CLICKED", now.minusSeconds(7 * DAY), now.minusSeconds(5 * DAY), now);
        long otherMovie = insertMovie(991_900, "타인 만료 영화");
        long other =
                insertWatch(
                        otherUserId,
                        otherMovie,
                        ottId,
                        "LINK_CLICKED",
                        now.minusSeconds(7 * DAY),
                        now);

        CursorPageResponse<WatchRecordItemResponse> response =
                listWithFixedClock(
                        now, WatchListCriteria.from(List.of("EXPIRED"), null, null, "20"));

        assertThat(response.items())
                .extracting(WatchRecordItemResponse::watchRecordId)
                .containsExactly(owned);
        assertThat(watch(owned).expiredAt()).isEqualTo(now);
        assertThat(watch(other).status()).isEqualTo("LINK_CLICKED");
    }

    @Test
    @DisplayName("서브마이크로초 now는 confirmDue를 일찍 노출하지 않고 정확한 경계부터 포함한다")
    void subMicroNowDoesNotExposeConfirmDueEarly() {
        Instant boundary = Instant.parse("2026-09-15T01:23:45.123457Z");
        Instant beforeBoundary = boundary.minusNanos(300);
        long recordId =
                insertDistinctWatch(
                        "LINK_CLICKED",
                        boundary.minusSeconds(2 * DAY),
                        boundary,
                        boundary.plusSeconds(DAY));
        WatchListCriteria criteria = WatchListCriteria.from(null, "true", null, "20");

        assertThat(listWithFixedClock(beforeBoundary, criteria).items()).isEmpty();
        assertThat(listWithFixedClock(boundary, criteria).items())
                .extracting(WatchRecordItemResponse::watchRecordId)
                .containsExactly(recordId);
        assertThat(listWithFixedClock(boundary.plusNanos(1_000), criteria).items())
                .extracting(WatchRecordItemResponse::watchRecordId)
                .containsExactly(recordId);
    }

    @Test
    @DisplayName("서브마이크로초 now는 GET 만료를 앞당기지 않고 정확한 경계부터 저장한다")
    void subMicroNowDoesNotExpireEarlyOnList() {
        Instant boundary = Instant.parse("2026-09-15T01:23:45.123457Z");
        Instant beforeBoundary = boundary.minusNanos(300);
        WatchListCriteria expiredCriteria =
                WatchListCriteria.from(List.of("EXPIRED"), null, null, "20");
        long exactRecord =
                insertDistinctWatch(
                        "LINK_CLICKED",
                        boundary.minusSeconds(7 * DAY),
                        boundary.minusSeconds(5 * DAY),
                        boundary);

        assertThat(listWithFixedClock(beforeBoundary, expiredCriteria).items()).isEmpty();
        assertThat(watch(exactRecord).status()).isEqualTo("LINK_CLICKED");
        assertThat(watch(exactRecord).expiredAt()).isNull();

        assertThat(listWithFixedClock(boundary, expiredCriteria).items())
                .extracting(WatchRecordItemResponse::watchRecordId)
                .containsExactly(exactRecord);
        assertThat(watch(exactRecord).expiredAt()).isEqualTo(boundary);

        long laterRecord =
                insertDistinctWatch(
                        "LINK_CLICKED",
                        boundary.minusSeconds(7 * DAY),
                        boundary.minusSeconds(5 * DAY),
                        boundary);
        Instant afterBoundary = boundary.plusNanos(1_000);

        assertThat(listWithFixedClock(afterBoundary, expiredCriteria).items())
                .extracting(WatchRecordItemResponse::watchRecordId)
                .contains(laterRecord);
        assertThat(watch(laterRecord).status()).isEqualTo("EXPIRED");
        assertThat(watch(laterRecord).expiredAt()).isEqualTo(afterBoundary);
    }

    @Test
    @DisplayName("서브마이크로초 now는 POST 확인을 허용하고 저장·응답 시각을 같은 값으로 절삭한다")
    void subMicroNowConfirmsBeforeExpiryAndRejectsAtBoundary() {
        Instant boundary = Instant.parse("2026-09-15T01:23:45.123457Z");
        Instant beforeBoundary = boundary.minusNanos(300);
        Instant normalizedNow = beforeBoundary.truncatedTo(ChronoUnit.MICROS);
        long watchedRecord =
                insertDistinctWatch(
                        "LINK_CLICKED",
                        boundary.minusSeconds(7 * DAY),
                        boundary.minusSeconds(5 * DAY),
                        boundary);
        long expiredRecord =
                insertDistinctWatch(
                        "LINK_CLICKED",
                        boundary.minusSeconds(7 * DAY),
                        boundary.minusSeconds(5 * DAY),
                        boundary);

        WatchConfirmResponse response = confirmWithFixedClock(beforeBoundary, watchedRecord, true);
        WatchConfirmResponse notWatched =
                confirmWithFixedClock(beforeBoundary, expiredRecord, false);

        assertThat(response.watchedAt()).isEqualTo(normalizedNow);
        assertThat(watch(watchedRecord).watchedAt()).isEqualTo(normalizedNow);
        assertThat(response.watchedAt()).isEqualTo(watch(watchedRecord).watchedAt());
        assertThat(notWatched.status().name()).isEqualTo("EXPIRED");
        assertThat(watch(expiredRecord).expiredAt()).isEqualTo(normalizedNow);

        long exactRecord =
                insertDistinctWatch(
                        "LINK_CLICKED",
                        boundary.minusSeconds(7 * DAY),
                        boundary.minusSeconds(5 * DAY),
                        boundary);
        long laterRecord =
                insertDistinctWatch(
                        "LINK_CLICKED",
                        boundary.minusSeconds(7 * DAY),
                        boundary.minusSeconds(5 * DAY),
                        boundary);

        assertBusinessError(
                () -> confirmWithFixedClock(boundary, exactRecord, true),
                ErrorCode.INVALID_WATCH_STATUS);
        assertBusinessError(
                () -> confirmWithFixedClock(boundary.plusNanos(1_000), laterRecord, true),
                ErrorCode.INVALID_WATCH_STATUS);
        assertThat(watch(exactRecord).status()).isEqualTo("LINK_CLICKED");
        assertThat(watch(laterRecord).status()).isEqualTo("LINK_CLICKED");
    }

    @Test
    @DisplayName("동일 시각은 ID 내림차순이고 null 시각은 마지막이며 다음 cursor가 안정적이다")
    void cursorPaginationIsStableForTiesAndNullTimes() throws Exception {
        Instant tied = Instant.parse("2026-09-14T10:00:00Z");
        long tiedLow = insertDistinctWatch("RATED", tied);
        long tiedHigh = insertDistinctWatch("RATED", tied);
        long older = insertDistinctWatch("RATED", tied.minusSeconds(1));
        long nullTimeLow = insertDistinctWatch("RATED", null);
        long nullTimeHigh = insertDistinctWatch("RATED", null);

        JsonNode first = response(list(userId, "size", "2").andExpect(status().isOk()).andReturn());
        assertThat(ids(first)).containsExactly(tiedHigh, tiedLow);
        assertThat(first.path("hasNext").asBoolean()).isTrue();

        JsonNode second =
                response(
                        list(userId, "size", "1", "cursor", first.path("nextCursor").asText())
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(ids(second)).containsExactly(older);
        assertThat(second.path("hasNext").asBoolean()).isTrue();

        JsonNode third =
                response(
                        list(userId, "size", "1", "cursor", second.path("nextCursor").asText())
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(ids(third)).containsExactly(nullTimeHigh);
        assertThat(third.path("hasNext").asBoolean()).isTrue();

        JsonNode fourth =
                response(
                        list(userId, "size", "50", "cursor", third.path("nextCursor").asText())
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(ids(fourth)).containsExactly(nullTimeLow);
        assertThat(fourth.path("hasNext").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("손상·필터 불일치·사용자 불일치 cursor는 모두 400이다")
    void rejectsDamagedFilterMismatchedAndUserMismatchedCursors() throws Exception {
        insertDistinctWatch("LINK_CLICKED", Instant.now());
        insertDistinctWatch("LINK_CLICKED", Instant.now().minusSeconds(1));
        JsonNode first =
                response(
                        list(userId, "status", "LINK_CLICKED", "size", "1")
                                .andExpect(status().isOk())
                                .andReturn());
        String cursor = first.path("nextCursor").asText();

        assertValidation(list(userId, "cursor", "not-base64!"));
        assertValidation(list(userId, "status", "RATED", "cursor", cursor));
        assertValidation(
                list(userId, "status", "LINK_CLICKED", "confirmDue", "true", "cursor", cursor));
        assertValidation(list(otherUserId, "status", "LINK_CLICKED", "cursor", cursor));
    }

    @Test
    @DisplayName("페이지 크기 기본 20·최소 1·최대 50을 적용한다")
    void supportsDefaultMinimumAndMaximumPageSizes() throws Exception {
        for (int index = 0; index < 51; index++) {
            insertDistinctWatch("RATED", Instant.now().minusSeconds(index));
        }

        assertThat(response(list(userId).andExpect(status().isOk()).andReturn()).path("items"))
                .hasSize(20);
        assertThat(
                        response(list(userId, "size", "1").andExpect(status().isOk()).andReturn())
                                .path("items"))
                .hasSize(1);
        assertThat(
                        response(list(userId, "size", "50").andExpect(status().isOk()).andReturn())
                                .path("items"))
                .hasSize(50);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "51", "-1", "+1", "1.0", "size"})
    @DisplayName("범위 밖 또는 정수가 아닌 size는 400이다")
    void rejectsInvalidPageSize(String invalid) throws Exception {
        assertValidation(list(userId, "size", invalid));
    }

    @Test
    @DisplayName("잘못된 status와 소문자 true/false가 아닌 confirmDue는 400이다")
    void rejectsInvalidFilters() throws Exception {
        assertValidation(list(userId, "status", "WATCHED"));
        assertValidation(list(userId, "status", ""));
        for (String invalid : List.of("TRUE", "False", "1", "")) {
            assertValidation(list(userId, "confirmDue", invalid));
        }
    }

    @ParameterizedTest
    @CsvSource({
        "status[0],RATED",
        "status[],RATED",
        "status[0].class,RATED",
        "status[-1],RATED",
        "confirmDue[0],true",
        "cursor[0],x",
        "size.class,1",
        "unknown[0],1"
    })
    @DisplayName("인덱스·중첩 파라미터 이름은 조건을 무시하지 않고 400으로 거부한다")
    void rejectsIndexedAndNestedParameterNames(String name, String value) throws Exception {
        insertDistinctWatch("LINK_CLICKED", Instant.now());

        assertRejectedWithoutList(list(userId, name, value));
        assertRejectedWithoutList(list(userId, "status", "RATED", name, value));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/watch-records?status%5B0%5D=RATED",
                "/api/v1/watch-records?status%5B%5D=RATED",
                "/api/v1/watch-records?status=RATED&confirmDue%5B0%5D=true"
            })
    @DisplayName("실제 URL 디코딩을 거친 인덱스 표기도 400이다")
    void rejectsIndexedParameterNamesAfterUrlDecoding(String url) throws Exception {
        insertDistinctWatch("LINK_CLICKED", Instant.now());

        assertRejectedWithoutList(
                mockMvc.perform(get(URI.create(url)).header("Authorization", bearer(userId))));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/watch-records?status%00=RATED",
                "/api/v1/watch-records?status%09=RATED",
                "/api/v1/watch-records?status=RATED&a%00b=1"
            })
    @DisplayName("금지 문자가 든 파라미터 이름은 500이 아니라 400이다")
    void rejectsForbiddenCharactersInParameterNamesWithoutServerError(String url) throws Exception {
        insertDistinctWatch("LINK_CLICKED", Instant.now());

        assertRejectedWithoutList(
                mockMvc.perform(get(URI.create(url)).header("Authorization", bearer(userId))));
    }

    @Test
    @DisplayName("계약이 정한 반복 지정은 그대로 동작한다")
    void keepsRepeatedArrayParametersWorking() throws Exception {
        long linked = insertDistinctWatch("LINK_CLICKED", Instant.now().minusSeconds(DAY));
        long rated = insertDistinctWatch("RATED", Instant.now().minusSeconds(2 * DAY));
        insertDistinctWatch("WATCHED_UNRATED", Instant.now().minusSeconds(3 * DAY));

        JsonNode response =
                response(
                        list(userId, "status", "LINK_CLICKED", "status", "RATED")
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(ids(response)).containsExactly(linked, rated);
    }

    @Test
    @DisplayName("인증되지 않은 인덱스 표기 요청은 검증보다 먼저 401로 거부한다")
    void rejectsIndexedParametersOnlyAfterAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/watch-records").param("status[0]", "RATED"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("같은 컨트롤러의 확인 요청은 질의 파라미터가 없으면 영향받지 않는다")
    void confirmIsUnaffectedByParameterNameCheck() throws Exception {
        long recordId = insertDistinctWatch("LINK_CLICKED", Instant.now());

        confirm(recordId, userId, "{\"watched\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WATCHED_UNRATED"));

        assertThat(watch(recordId).status()).isEqualTo("WATCHED_UNRATED");
    }

    @Test
    @DisplayName("확인 요청의 인덱스 표기 질의도 400이며 상태를 바꾸지 않는다")
    void rejectsIndexedParametersOnConfirm() throws Exception {
        long recordId = insertDistinctWatch("LINK_CLICKED", Instant.now());

        assertValidation(
                mockMvc.perform(
                        post(confirmPath(recordId))
                                .param("x[0]", "1")
                                .header("Authorization", bearer(userId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"watched\":true}")));

        assertThat(watch(recordId).status()).isEqualTo("LINK_CLICKED");
    }

    @Test
    @DisplayName("확인 요청의 금지 문자 파라미터 이름도 500이 아니라 400이다")
    void rejectsForbiddenCharactersOnConfirmWithoutServerError() throws Exception {
        long recordId = insertDistinctWatch("LINK_CLICKED", Instant.now());

        assertValidation(
                mockMvc.perform(
                        post(URI.create(confirmPath(recordId) + "?a%00b=1"))
                                .header("Authorization", bearer(userId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"watched\":true}")));

        assertThat(watch(recordId).status()).isEqualTo("LINK_CLICKED");
    }

    @Test
    @DisplayName("봤어요는 조기 응답도 허용하고 WATCHED_UNRATED와 watchedAt을 저장한다")
    void confirmsWatchedBeforeDueTime() {
        Instant now = Instant.parse("2026-09-15T01:23:45.123456Z");
        long recordId =
                insertDistinctWatch(
                        "LINK_CLICKED", now, now.plusSeconds(2 * DAY), now.plusSeconds(7 * DAY));

        WatchConfirmResponse response = confirmWithFixedClock(now, recordId, true);

        assertThat(response.status().name()).isEqualTo("WATCHED_UNRATED");
        assertThat(response.watchedAt()).isEqualTo(now);
        WatchRow stored = watch(recordId);
        assertThat(stored.status()).isEqualTo("WATCHED_UNRATED");
        assertThat(stored.watchedAt()).isEqualTo(now);
        assertThat(stored.expiredAt()).isNull();
    }

    @Test
    @DisplayName("안 봤어요는 EXPIRED와 expiredAt을 저장한다")
    void confirmsNotWatched() throws Exception {
        long recordId = insertDistinctWatch("LINK_CLICKED", Instant.now().minusSeconds(DAY));
        Instant before = Instant.now();

        JsonNode response =
                response(
                        confirm(recordId, userId, "{\"watched\":false}")
                                .andExpect(status().isOk())
                                .andReturn());

        Instant after = Instant.now();
        assertThat(response.path("status").asText()).isEqualTo("EXPIRED");
        assertThat(response.path("watchedAt").isNull()).isTrue();
        WatchRow stored = watch(recordId);
        assertThat(stored.status()).isEqualTo("EXPIRED");
        assertThat(stored.expiredAt()).isBetween(before, after);
        assertThat(stored.watchedAt()).isNull();
    }

    @Test
    @DisplayName("타인 기록과 미존재 기록은 같은 404로 숨긴다")
    void hidesOtherUsersRecordsAsNotFound() throws Exception {
        long otherMovie = insertMovie(992_001, "타인 확인 영화");
        long otherRecord =
                insertWatch(
                        otherUserId,
                        otherMovie,
                        ottId,
                        "LINK_CLICKED",
                        Instant.now(),
                        Instant.now().plusSeconds(7 * DAY));

        confirm(otherRecord, userId, "{\"watched\":true}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        confirm(otherRecord + 99_999, userId, "{\"watched\":true}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(watch(otherRecord).status()).isEqualTo("LINK_CLICKED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"WATCHED_UNRATED", "RATED", "EXPIRED"})
    @DisplayName("이미 완료·평가·만료 상태는 409로 거절한다")
    void rejectsNonLinkClickedStates(String currentStatus) throws Exception {
        long recordId = insertDistinctWatch(currentStatus, Instant.now().minusSeconds(DAY));

        confirm(recordId, userId, "{\"watched\":true}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_WATCH_STATUS"));
        assertThat(watch(recordId).status()).isEqualTo(currentStatus);
    }

    @Test
    @DisplayName("중복 확인과 expiresAt == now 확인은 409이며 원래 상태를 안전하게 보존한다")
    void rejectsDuplicateAndExactExpirationBoundary() throws Exception {
        long duplicate = insertDistinctWatch("LINK_CLICKED", Instant.now().minusSeconds(DAY));
        confirm(duplicate, userId, "{\"watched\":true}").andExpect(status().isOk());
        confirm(duplicate, userId, "{\"watched\":true}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_WATCH_STATUS"));

        Instant now = Instant.parse("2026-09-15T01:23:45.123456Z");
        long expired =
                insertDistinctWatch(
                        "LINK_CLICKED", now.minusSeconds(7 * DAY), now.minusSeconds(5 * DAY), now);
        assertBusinessError(
                () -> confirmWithFixedClock(now, expired, true), ErrorCode.INVALID_WATCH_STATUS);
        assertThat(watch(expired).status()).isEqualTo("LINK_CLICKED");
        assertThat(watch(expired).watchedAt()).isNull();
    }

    @Test
    @DisplayName("watched는 JSON boolean만 허용하고 깨진·누락·null 본문을 400으로 거절한다")
    void validatesStrictBooleanBody() throws Exception {
        long recordId = insertDistinctWatch("LINK_CLICKED", Instant.now());
        for (String invalid :
                List.of(
                        "{}",
                        "{\"watched\":null}",
                        "{\"watched\":\"true\"}",
                        "{\"watched\":1}",
                        "{\"watched\":[]}",
                        "{\"watched\":{}}",
                        "{\"watched\":",
                        "")) {
            confirm(recordId, userId, invalid)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            assertThat(watch(recordId).status()).isEqualTo("LINK_CLICKED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-a-number", "9223372036854775808"})
    @DisplayName("watchRecordId는 양의 64비트 정수만 허용한다")
    void validatesWatchRecordId(String invalidId) throws Exception {
        mockMvc.perform(
                        post("/api/v1/watch-records/" + invalidId + "/confirm")
                                .header("Authorization", bearer(userId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"watched\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(count("watch_records")).isZero();
    }

    @Test
    @DisplayName("알 수 없는 JSON 필드는 허용하고 Content-Type 누락·비지원은 415다")
    void allowsUnknownFieldsAndRequiresJsonContentType() throws Exception {
        long accepted = insertDistinctWatch("LINK_CLICKED", Instant.now());
        confirm(accepted, userId, "{\"watched\":true,\"futureField\":\"ok\"}")
                .andExpect(status().isOk());

        long missing = insertDistinctWatch("LINK_CLICKED", Instant.now());
        mockMvc.perform(
                        post(confirmPath(missing))
                                .header("Authorization", bearer(userId))
                                .content("{\"watched\":true}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mockMvc.perform(
                        post(confirmPath(missing))
                                .header("Authorization", bearer(userId))
                                .contentType(MediaType.TEXT_PLAIN)
                                .content("{\"watched\":true}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        assertThat(watch(missing).status()).isEqualTo("LINK_CLICKED");
    }

    @Test
    @DisplayName("인증이 없거나 활성 사용자가 아니면 조회·확인을 401로 거절한다")
    void requiresAuthenticatedActiveUser() throws Exception {
        long recordId = insertDistinctWatch("LINK_CLICKED", Instant.now());
        mockMvc.perform(get("/api/v1/watch-records"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        list(withdrawnUserId)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        confirm(recordId, withdrawnUserId, "{\"watched\":true}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(watch(recordId).status()).isEqualTo("LINK_CLICKED");
    }

    @Test
    @DisplayName("만료 반영 뒤 목록 SELECT 실패는 500이며 선행 만료 반영도 롤백한다")
    void selectFailureRollsBackPriorExpiration() throws Exception {
        long recordId =
                insertDistinctWatch(
                        "LINK_CLICKED",
                        Instant.now().minusSeconds(8 * DAY),
                        Instant.now().minusSeconds(6 * DAY),
                        Instant.now().minusSeconds(DAY));

        jdbc.sql("ALTER TABLE movies RENAME TO test_movies_hidden").update();
        try {
            list(userId)
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        } finally {
            jdbc.sql("ALTER TABLE test_movies_hidden RENAME TO movies").update();
        }

        assertThat(watch(recordId).status()).isEqualTo("LINK_CLICKED");
        assertThat(watch(recordId).expiredAt()).isNull();
    }

    @Test
    @DisplayName("commit 시점 실패는 500이며 감상 확인 전이를 롤백한다")
    void commitFailureRollsBackConfirmation() throws Exception {
        long recordId = insertDistinctWatch("LINK_CLICKED", Instant.now());
        failStatusTransitionAtCommit();

        confirm(recordId, userId, "{\"watched\":true}")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        WatchRow stored = watch(recordId);
        assertThat(stored.status()).isEqualTo("LINK_CLICKED");
        assertThat(stored.watchedAt()).isNull();
    }

    @Test
    @DisplayName("조건부 update가 0행이면 경쟁 전이로 409를 반환한다")
    void zeroRowConditionalUpdateIsConflict() throws Exception {
        long recordId = insertDistinctWatch("LINK_CLICKED", Instant.now());
        skipConfirmationUpdate();

        confirm(recordId, userId, "{\"watched\":true}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_WATCH_STATUS"));
        assertThat(watch(recordId).status()).isEqualTo("LINK_CLICKED");
    }

    @Test
    @DisplayName("확인은 rating·한줄평·frame·영화 집계·outbox를 변경하지 않는다")
    void confirmationDoesNotMutateRatingFrameMovieOrOutbox() throws Exception {
        long ratedMovie = insertMovie(993_001, "평가 보존 영화");
        long ratedWatch =
                insertWatch(
                        userId,
                        ratedMovie,
                        ottId,
                        "RATED",
                        Instant.now().minusSeconds(DAY),
                        Instant.now().plusSeconds(DAY));
        long ratingId = insertRating(ratedWatch, userId, ratedMovie, "보존할 한줄평");
        insertFrame(ratingId, userId, ratedMovie);
        long target = insertDistinctWatch("LINK_CLICKED", Instant.now());
        RatingRow before = rating(ratingId);
        String movieAggregateBefore = movieAggregate(ratedMovie);

        confirm(target, userId, "{\"watched\":true}").andExpect(status().isOk());

        assertThat(rating(ratingId)).isEqualTo(before);
        assertThat(count("frames")).isEqualTo(1);
        assertThat(movieAggregate(ratedMovie)).isEqualTo(movieAggregateBefore);
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    @DisplayName("동일 기록 동시 confirm은 실제 사용자 행 잠금 대기 후 하나만 성공한다")
    void concurrentConfirmSerializesWithoutLostUpdate() throws Exception {
        long recordId = insertDistinctWatch("LINK_CLICKED", Instant.now());

        LockedConcurrentResult concurrent =
                concurrentWithObservedUserLock(
                        () -> concurrentConfirm(recordId, true),
                        () -> concurrentConfirm(recordId, false));

        assertObservedLockWait(concurrent);
        assertThat(concurrent.responses())
                .extracting(ConcurrentResponse::status)
                .containsExactlyInAnyOrder(200, 409);
        assertThat(watch(recordId).status()).isIn("WATCHED_UNRATED", "EXPIRED");
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    @DisplayName("API-033 재클릭과 confirm 경쟁은 같은 잠금 순서로 deadlock·lost update가 없다")
    void reclickAndConfirmRaceKeepsConfirmedStateAndOneOutbox() throws Exception {
        long recordId =
                insertWatch(
                        userId,
                        movieId,
                        ottId,
                        "LINK_CLICKED",
                        Instant.now().minusSeconds(DAY),
                        Instant.now().plusSeconds(6 * DAY));

        LockedConcurrentResult concurrent =
                concurrentWithObservedUserLock(
                        () -> concurrentClick(movieId, ottId),
                        () -> concurrentConfirm(recordId, true));

        assertObservedLockWait(concurrent);
        assertThat(concurrent.responses()).extracting(ConcurrentResponse::status).containsOnly(200);
        assertThat(watch(recordId).status()).isEqualTo("WATCHED_UNRATED");
        assertThat(count("watch_records")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM outbox_events").query(String.class).single())
                .isEqualTo("PENDING");
    }

    private ResultActions list(long user) throws Exception {
        return mockMvc.perform(get("/api/v1/watch-records").header("Authorization", bearer(user)));
    }

    private ResultActions list(long user, String... queryPairs) throws Exception {
        var request = get("/api/v1/watch-records").header("Authorization", bearer(user));
        for (int index = 0; index < queryPairs.length; index += 2) {
            request.param(queryPairs[index], queryPairs[index + 1]);
        }
        return mockMvc.perform(request);
    }

    private ResultActions confirm(long recordId, long user, String body) throws Exception {
        return mockMvc.perform(
                post(confirmPath(recordId))
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private ConcurrentResponse concurrentConfirm(long recordId, boolean watched) throws Exception {
        MvcResult result = confirm(recordId, userId, "{\"watched\":" + watched + "}").andReturn();
        return new ConcurrentResponse(result.getResponse().getStatus(), response(result));
    }

    private ConcurrentResponse concurrentClick(long movie, long ott) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/movies/" + movie + "/ott-clicks")
                                        .header("Authorization", bearer(userId))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"ottId\":" + ott + "}"))
                        .andReturn();
        return new ConcurrentResponse(result.getResponse().getStatus(), response(result));
    }

    private CursorPageResponse<WatchRecordItemResponse> listWithFixedClock(
            Instant now, WatchListCriteria criteria) {
        WatchService service = fixedService(now);
        CursorPageResponse<WatchRecordItemResponse> response =
                new TransactionTemplate(transactionManager)
                        .execute(status -> service.getWatchRecords(userId, criteria));
        assertThat(response).isNotNull();
        return response;
    }

    private WatchConfirmResponse confirmWithFixedClock(
            Instant now, long recordId, boolean watched) {
        WatchService service = fixedService(now);
        WatchConfirmResponse response =
                new TransactionTemplate(transactionManager)
                        .execute(status -> service.confirm(userId, recordId, watched));
        assertThat(response).isNotNull();
        return response;
    }

    private WatchService fixedService(Instant now) {
        return new WatchService(watchRepository, eventPublisher, Clock.fixed(now, ZoneOffset.UTC));
    }

    /** 거절이 목록을 대신 흘려보내지 않는지까지 본다. 무시하고 200을 주던 결함이 이 API의 문제였다. */
    private void assertRejectedWithoutList(ResultActions actions) throws Exception {
        assertValidation(actions);
        actions.andExpect(jsonPath("$.items").doesNotExist());
    }

    private void assertValidation(ResultActions actions) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private static void assertBusinessError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private JsonNode response(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<Long> ids(JsonNode response) {
        List<Long> ids = new ArrayList<>();
        response.path("items").forEach(item -> ids.add(item.path("watchRecordId").asLong()));
        return ids;
    }

    private String confirmPath(long recordId) {
        return "/api/v1/watch-records/" + recordId + "/confirm";
    }

    private String bearer(long user) {
        return "Bearer " + tokenProvider.createAccessToken(user);
    }

    private long insertDistinctWatch(String status, Instant clickedAt) {
        Instant expiresAt = clickedAt == null ? null : Instant.now().plusSeconds(10 * DAY);
        int tmdbId = 994_000 + (int) count("movies");
        long movie = insertMovie(tmdbId, "목록 영화 " + tmdbId);
        link(movie, ottId);
        return insertWatch(userId, movie, ottId, status, clickedAt, expiresAt);
    }

    private long insertDistinctWatch(
            String status, Instant clickedAt, Instant confirmDueAt, Instant expiresAt) {
        int tmdbId = 994_000 + (int) count("movies");
        long movie = insertMovie(tmdbId, "목록 영화 " + tmdbId);
        link(movie, ottId);
        return insertWatch(userId, movie, ottId, status, clickedAt, confirmDueAt, expiresAt);
    }

    private long insertWatch(
            long user, long movie, Long ott, String status, Instant clickedAt, Instant expiresAt) {
        Instant dueAt = clickedAt == null ? null : clickedAt.plusSeconds(2 * DAY);
        return insertWatch(user, movie, ott, status, clickedAt, dueAt, expiresAt);
    }

    private long insertWatch(
            long user,
            long movie,
            Long ott,
            String status,
            Instant clickedAt,
            Instant confirmDueAt,
            Instant expiresAt) {
        Instant watchedAt =
                status.equals("WATCHED_UNRATED") || status.equals("RATED")
                        ? Instant.parse("2026-09-01T00:00:00Z")
                        : null;
        Instant ratedAt = status.equals("RATED") ? Instant.parse("2026-09-02T00:00:00Z") : null;
        Instant expiredAt = status.equals("EXPIRED") ? Instant.parse("2026-09-03T00:00:00Z") : null;
        return jdbc.sql(
                        """
                        INSERT INTO watch_records(
                            user_id,movie_id,ott_id,status,clicked_at,confirm_due_at,expires_at,
                            watched_at,rated_at,expired_at
                        ) VALUES (
                            :user,:movie,:ott,:status,:clicked,:due,:expires,:watched,:rated,:expired
                        ) RETURNING id
                        """)
                .param("user", user)
                .param("movie", movie)
                .param("ott", ott)
                .param("status", status)
                .param("clicked", offset(clickedAt))
                .param("due", offset(confirmDueAt))
                .param("expires", offset(expiresAt))
                .param("watched", offset(watchedAt))
                .param("rated", offset(ratedAt))
                .param("expired", offset(expiredAt))
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

    private long insertMovie(int tmdbId, String title) {
        return jdbc.sql(
                        "INSERT INTO movies(tmdb_id,title,poster_url)"
                                + " VALUES (:tmdb,:title,:poster) RETURNING id")
                .param("tmdb", tmdbId)
                .param("title", title)
                .param("poster", "https://image.test/" + tmdbId + ".jpg")
                .query(Long.class)
                .single();
    }

    private long insertOtt(String code, int providerId) {
        return jdbc.sql(
                        "INSERT INTO otts(code,name,tmdb_provider_id)"
                                + " VALUES (:code,:code,:provider) RETURNING id")
                .param("code", code)
                .param("provider", providerId)
                .query(Long.class)
                .single();
    }

    private void link(long movie, long ott) {
        jdbc.sql("INSERT INTO movie_ott_providers(movie_id,ott_id) VALUES (:movie,:ott)")
                .param("movie", movie)
                .param("ott", ott)
                .update();
    }

    private long insertRating(long watchRecord, long user, long movie, String comment) {
        return jdbc.sql(
                        """
                        INSERT INTO ratings(
                            user_id,movie_id,watch_record_id,score,comment,visibility
                        ) VALUES (:user,:movie,:watch,4.5,:comment,'PRIVATE') RETURNING id
                        """)
                .param("user", user)
                .param("movie", movie)
                .param("watch", watchRecord)
                .param("comment", comment)
                .query(Long.class)
                .single();
    }

    private void insertFrame(long rating, long user, long movie) {
        jdbc.sql("INSERT INTO frames(rating_id,user_id,movie_id) VALUES (:rating,:user,:movie)")
                .param("rating", rating)
                .param("user", user)
                .param("movie", movie)
                .update();
    }

    private WatchRow watch(long id) {
        return jdbc.sql(
                        """
                        SELECT status,clicked_at,confirm_due_at,expires_at,watched_at,expired_at
                        FROM watch_records WHERE id=:id
                        """)
                .param("id", id)
                .query(
                        (row, number) ->
                                new WatchRow(
                                        row.getString("status"),
                                        instant(row, "clicked_at"),
                                        instant(row, "confirm_due_at"),
                                        instant(row, "expires_at"),
                                        instant(row, "watched_at"),
                                        instant(row, "expired_at")))
                .single();
    }

    private RatingRow rating(long id) {
        return jdbc.sql(
                        "SELECT score::text,comment,visibility,watch_record_id"
                                + " FROM ratings WHERE id=:id")
                .param("id", id)
                .query(
                        (row, number) ->
                                new RatingRow(
                                        row.getString(1),
                                        row.getString(2),
                                        row.getString(3),
                                        row.getLong(4)))
                .single();
    }

    private String movieAggregate(long movie) {
        return jdbc.sql(
                        "SELECT COALESCE(average_rating::text,'null') || ':' || rating_count::text"
                                + " FROM movies WHERE id=:id")
                .param("id", movie)
                .query(String.class)
                .single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private void truncate() {
        jdbc.sql(
                        "TRUNCATE TABLE outbox_events, frames, ratings, watch_records,"
                                + " movie_ott_providers, movies, otts, users"
                                + " RESTART IDENTITY CASCADE")
                .update();
    }

    private void failStatusTransitionAtCommit() {
        jdbc.sql(
                        """
                        CREATE FUNCTION test_fail_watch_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN RAISE EXCEPTION 'forced watch commit failure'; END; $$
                        """)
                .update();
        jdbc.sql(
                        """
                        CREATE CONSTRAINT TRIGGER test_fail_watch_commit
                        AFTER UPDATE ON watch_records DEFERRABLE INITIALLY DEFERRED
                        FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status)
                        EXECUTE FUNCTION test_fail_watch_commit()
                        """)
                .update();
    }

    private void skipConfirmationUpdate() {
        jdbc.sql(
                        """
                        CREATE FUNCTION test_skip_watch_confirm() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN RETURN NULL; END; $$
                        """)
                .update();
        jdbc.sql(
                        """
                        CREATE TRIGGER test_skip_watch_confirm BEFORE UPDATE OF status ON watch_records
                        FOR EACH ROW WHEN (NEW.status IN ('WATCHED_UNRATED', 'EXPIRED'))
                        EXECUTE FUNCTION test_skip_watch_confirm()
                        """)
                .update();
    }

    private LockedConcurrentResult concurrentWithObservedUserLock(
            Callable<ConcurrentResponse> firstCall, Callable<ConcurrentResponse> secondCall)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<ConcurrentResponse> first = null;
        Future<ConcurrentResponse> second = null;
        try (Connection lockConnection = dataSource.getConnection();
                Connection observerConnection = dataSource.getConnection()) {
            lockConnection.setAutoCommit(false);
            boolean released = false;
            try {
                int blockerPid = backendPid(lockConnection);
                lockUser(lockConnection, userId);
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                first = executor.submit(() -> startTogether(firstCall, ready, start));
                second = executor.submit(() -> startTogether(secondCall, ready, start));
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                List<LockWaitObservation> waits =
                        awaitUserLockWaits(observerConnection, 2, Duration.ofSeconds(10));
                assertThat(first.isDone()).isFalse();
                assertThat(second.isDone()).isFalse();
                lockConnection.commit();
                released = true;
                return new LockedConcurrentResult(
                        List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)),
                        blockerPid,
                        waits);
            } finally {
                if (!released) {
                    lockConnection.rollback();
                }
            }
        } finally {
            stopExecutor(executor, first, second);
        }
    }

    private static ConcurrentResponse startTogether(
            Callable<ConcurrentResponse> call, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return call.call();
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static void lockUser(Connection connection, long user) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT id FROM users WHERE id=? FOR NO KEY UPDATE")) {
            statement.setLong(1, user);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
            }
        }
    }

    private static List<LockWaitObservation> awaitUserLockWaits(
            Connection observer, int expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<LockWaitObservation> latest = List.of();
        while (System.nanoTime() < deadline) {
            latest = userLockWaits(observer);
            if (latest.size() >= expected) {
                return latest;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("Expected PostgreSQL user lock waiters, observed " + latest);
    }

    private static List<LockWaitObservation> userLockWaits(Connection observer)
            throws SQLException {
        List<LockWaitObservation> waits = new ArrayList<>();
        try (Statement statement = observer.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                """
                                SELECT pid,wait_event_type,state,query,pg_blocking_pids(pid) blockers
                                FROM pg_stat_activity
                                WHERE datname=current_database() AND pid<>pg_backend_pid()
                                  AND wait_event_type='Lock'
                                  AND query ILIKE '%FROM users%'
                                  AND query ILIKE '%FOR NO KEY UPDATE%'
                                ORDER BY pid
                                """)) {
            while (result.next()) {
                waits.add(
                        new LockWaitObservation(
                                result.getInt("pid"),
                                result.getString("wait_event_type"),
                                result.getString("state"),
                                result.getString("query"),
                                blockingPids(result)));
            }
        }
        return waits;
    }

    private static List<Integer> blockingPids(ResultSet result) throws SQLException {
        Array array = result.getArray("blockers");
        try {
            return Arrays.stream((Object[]) array.getArray())
                    .map(value -> ((Number) value).intValue())
                    .toList();
        } finally {
            array.free();
        }
    }

    private static void assertObservedLockWait(LockedConcurrentResult concurrent) {
        assertThat(concurrent.waits()).hasSize(2);
        assertThat(concurrent.waits())
                .allSatisfy(
                        wait -> {
                            assertThat(wait.waitEventType()).isEqualTo("Lock");
                            assertThat(wait.state()).isEqualTo("active");
                            assertThat(wait.query()).containsIgnoringCase("FOR NO KEY UPDATE");
                            assertThat(wait.blockingPids()).isNotEmpty();
                        });
        assertThat(concurrent.waits())
                .anySatisfy(
                        wait -> assertThat(wait.blockingPids()).contains(concurrent.blockerPid()));
    }

    private static void stopExecutor(ExecutorService executor, Future<?> first, Future<?> second)
            throws InterruptedException {
        for (Future<?> future : new Future<?>[] {first, second}) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Object offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private record WatchRow(
            String status,
            Instant clickedAt,
            Instant confirmDueAt,
            Instant expiresAt,
            Instant watchedAt,
            Instant expiredAt) {}

    private record RatingRow(String score, String comment, String visibility, long watchRecordId) {}

    private record ConcurrentResponse(int status, JsonNode body) {}

    private record LockedConcurrentResult(
            List<ConcurrentResponse> responses, int blockerPid, List<LockWaitObservation> waits) {}

    private record LockWaitObservation(
            int pid,
            String waitEventType,
            String state,
            String query,
            List<Integer> blockingPids) {}
}
