package com.feelm.backend.domain.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.watch.repository.MyRatingQueryRepository;
import com.feelm.backend.global.security.JjwtTokenProvider;
import com.feelm.backend.global.security.JwtProperties;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@DisplayName("내 평가·미평가 통합 조회 API 계약 — API-039")
class MyRatingApiContractTest extends SharedPostgresContractTest {

    private static final Instant BASE = Instant.parse("2026-09-17T01:00:00Z");
    private static final String PATH = "/api/v1/users/me/ratings";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private JdbcClient jdbc;
    @Autowired private MyRatingQueryRepository queryRepository;

    private long userId;
    private long otherUserId;

    @BeforeEach
    void setUp() {
        truncate();
        userId = insertUser("ACTIVE");
        otherUserId = insertUser("ACTIVE");
    }

    @AfterEach
    void cleanUp() {
        truncate();
    }

    @Test
    void ownPublicAndPrivate() throws Exception {
        long first = insertMovie("첫 영화", "https://poster.test/one");
        long second = insertMovie("둘째 영화", null);
        long hidden = insertMovie("타인 영화", null);
        insertRating(userId, first, "4.5", "내 공개", "PUBLIC", true, BASE);
        insertRating(userId, second, "3.0", "내 비공개", "PRIVATE", false, BASE.plusSeconds(1));
        insertRating(otherUserId, hidden, "5.0", "타인 비공개", "PRIVATE", false, BASE);

        JsonNode items = ok(userId, null, null, null, null).path("items");
        assertThat(items.size()).isEqualTo(2);
        assertThat(movieIds(items)).containsExactly(second, first);
        assertThat(items.get(0).path("visibility").asText()).isEqualTo("PRIVATE");
        assertThat(items.get(1).path("visibility").asText()).isEqualTo("PUBLIC");
        assertThat(items.toString()).doesNotContain("타인 비공개");
    }

    @Test
    void otherOwnerCannotRead() throws Exception {
        long movieId = insertMovie("다른 사람 영화", null);
        insertRating(otherUserId, movieId, "5.0", "비밀", "PRIVATE", false, BASE);
        JsonNode response = ok(userId, null, null, null, null);
        assertThat(response.path("items").size()).isZero();
        assertThat(response.path("nextCursor").isNull()).isTrue();
        assertThat(response.path("hasNext").asBoolean()).isFalse();
    }

    @Test
    void scoreOnlyRating() throws Exception {
        long movieId = insertMovie("점수만", null);
        insertRating(userId, movieId, "4.0", null, null, false, BASE);
        JsonNode item = ok(userId, null, null, null, null).path("items").get(0);
        assertThat(item.path("itemType").asText()).isEqualTo("RATED");
        assertThat(item.path("score").decimalValue()).isEqualByComparingTo("4.0");
        assertThat(item.path("comment").isNull()).isTrue();
        assertThat(item.path("visibility").isNull()).isTrue();
        assertThat(item.path("spoiler").isBoolean()).isTrue();
        assertThat(item.path("spoiler").asBoolean()).isFalse();
        assertThat(item.path("frameId").isNull()).isTrue();
    }

    @Test
    void spoilerAndVisibilityAreIndependent() throws Exception {
        long first = insertMovie("공개 스포", null);
        long second = insertMovie("비공개 스포", null);
        insertRating(userId, first, "4.0", "결말", "PUBLIC", true, BASE);
        insertRating(userId, second, "4.5", "반전", "PRIVATE", true, BASE.plusSeconds(1));
        JsonNode items = ok(userId, null, null, null, null).path("items");
        assertThat(items.get(0).path("comment").asText()).isEqualTo("반전");
        assertThat(items.get(0).path("spoiler").asBoolean()).isTrue();
        assertThat(items.get(0).path("visibility").asText()).isEqualTo("PRIVATE");
        assertThat(items.get(1).path("comment").asText()).isEqualTo("결말");
        assertThat(items.get(1).path("spoiler").asBoolean()).isTrue();
        assertThat(items.get(1).path("visibility").asText()).isEqualTo("PUBLIC");
    }

    @Test
    void movieProjection() throws Exception {
        long first = insertMovie("포스터 있음", "https://poster.test/one");
        long second = insertMovie("포스터 없음", null);
        insertRating(userId, first, "4.0", null, null, false, BASE);
        insertRating(userId, second, "4.5", null, null, false, BASE.plusSeconds(1));
        JsonNode items = ok(userId, null, null, null, null).path("items");
        assertThat(items.get(0).path("movie").path("movieId").asLong()).isEqualTo(second);
        assertThat(items.get(0).path("movie").path("posterUrl").isNull()).isTrue();
        assertThat(items.get(1).path("movie").path("title").asText()).isEqualTo("포스터 있음");
        assertThat(items.get(1).path("movie").path("posterUrl").asText())
                .isEqualTo("https://poster.test/one");
    }

    @Test
    void frameProjection() throws Exception {
        long movieId = insertMovie("프레임", null);
        long ratingId = insertRating(userId, movieId, "5.0", null, null, false, BASE);
        long frameId = insertFrame(ratingId, userId, movieId);
        JsonNode item = ok(userId, null, null, null, null).path("items").get(0);
        assertThat(item.path("frameId").asLong()).isEqualTo(frameId);
    }

    @Test
    void emptyList() throws Exception {
        JsonNode response = ok(userId, null, null, null, null);
        assertThat(response.path("items").size()).isZero();
        assertThat(response.path("nextCursor").isNull()).isTrue();
        assertThat(response.path("hasNext").asBoolean()).isFalse();
    }

    @Test
    void orderAndTieBreak() throws Exception {
        long older = insertMovie("오래된", null);
        long first = insertMovie("동률1", null);
        long second = insertMovie("동률2", null);
        insertRating(userId, older, "4.0", null, null, false, BASE.minusSeconds(1));
        insertRating(userId, first, "4.0", null, null, false, BASE);
        insertRating(userId, second, "4.0", null, null, false, BASE);
        assertThat(movieIds(ok(userId, null, null, null, null).path("items")))
                .containsExactly(second, first, older);
    }

    @Test
    void defaultAndSizeBounds() throws Exception {
        for (int i = 0; i < 21; i++) {
            insertRating(
                    userId,
                    insertMovie("영화" + i, null),
                    "4.0",
                    null,
                    null,
                    false,
                    BASE.plusSeconds(i));
        }
        assertThat(ok(userId, null, null, null, null).path("items").size()).isEqualTo(20);
        assertThat(ok(userId, null, null, null, "1").path("items").size()).isEqualTo(1);
        assertThat(ok(userId, null, null, null, "50").path("items").size()).isEqualTo(21);
    }

    @Test
    void invalidSize() throws Exception {
        for (String size : List.of("0", "51", "-1", "1.5", "1e1", " 1", "1 ", "1,1")) {
            assertError(userId, null, null, null, size, 400, "VALIDATION_FAILED");
        }
    }

    @Test
    void duplicateAndUnknownQueryValues() throws Exception {
        for (String filter : List.of("", "RATED", "rated", "ALL,UNRATED", "UNKNOWN")) {
            assertError(userId, filter, null, null, null, 400, "VALIDATION_FAILED");
        }
        for (String sort : List.of("", "latest", "SCORE", "LATEST,OLDEST")) {
            assertError(userId, null, sort, null, null, 400, "VALIDATION_FAILED");
        }
        MvcResult duplicateSize =
                mockMvc.perform(
                                get(PATH)
                                        .header(
                                                "Authorization",
                                                "Bearer " + tokenProvider.createAccessToken(userId))
                                        .param("size", "1", "2"))
                        .andExpect(status().isBadRequest())
                        .andReturn();
        assertThat(
                        objectMapper
                                .readTree(duplicateSize.getResponse().getContentAsString())
                                .path("code")
                                .asText())
                .isEqualTo("VALIDATION_FAILED");
        for (String key : List.of("filter", "sort", "cursor")) {
            MvcResult duplicated =
                    mockMvc.perform(
                                    get(PATH)
                                            .header(
                                                    "Authorization",
                                                    "Bearer "
                                                            + tokenProvider.createAccessToken(
                                                                    userId))
                                            .param(key, "ALL", "ALL"))
                            .andExpect(status().isBadRequest())
                            .andReturn();
            assertThat(
                            objectMapper
                                    .readTree(duplicated.getResponse().getContentAsString())
                                    .path("code")
                                    .asText())
                    .as(key)
                    .isEqualTo("VALIDATION_FAILED");
        }
    }

    @Test
    void pageBoundary() throws Exception {
        for (int i = 0; i < 3; i++) {
            insertRating(
                    userId,
                    insertMovie("영화" + i, null),
                    "4.0",
                    null,
                    null,
                    false,
                    BASE.plusSeconds(i));
        }
        JsonNode first = ok(userId, null, null, null, "2");
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        String cursor = first.path("nextCursor").asText();
        JsonNode last = ok(userId, null, null, cursor, "2");
        assertThat(last.path("items").size()).isEqualTo(1);
        assertThat(last.path("hasNext").asBoolean()).isFalse();
        assertThat(last.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void microsTiePagination() throws Exception {
        Instant time = BASE.plusNanos(123_456_000);
        for (int i = 0; i < 5; i++) {
            insertRating(userId, insertMovie("동률" + i, null), "4.0", null, null, false, time);
        }
        assertThat(allPages(userId, null, null, 2)).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    void pageSizeChange() throws Exception {
        for (int i = 0; i < 5; i++) {
            insertRating(userId, insertMovie("영화" + i, null), "4.0", null, null, false, BASE);
        }
        JsonNode first = ok(userId, null, null, null, "2");
        JsonNode second = ok(userId, null, null, first.path("nextCursor").asText(), "3");
        List<Long> ids = new ArrayList<>(movieIds(first.path("items")));
        ids.addAll(movieIds(second.path("items")));
        assertThat(ids).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    void invalidCursor() throws Exception {
        for (String cursor : List.of("", "!", "abc", "a".repeat(200))) {
            assertError(userId, null, null, cursor, null, 400, "VALIDATION_FAILED");
        }
    }

    @Test
    void crossUserCursor() throws Exception {
        for (int i = 0; i < 2; i++) {
            insertRating(userId, insertMovie("영화" + i, null), "4.0", null, null, false, BASE);
        }
        String cursor = ok(userId, null, null, null, "1").path("nextCursor").asText();
        assertError(otherUserId, null, null, cursor, "1", 400, "VALIDATION_FAILED");
    }

    @Test
    void crossFilterAndSortCursor() throws Exception {
        for (int i = 0; i < 2; i++) {
            insertRating(userId, insertMovie("영화" + i, null), "4.0", "내용", "PUBLIC", false, BASE);
        }
        String cursor = ok(userId, "ALL", "LATEST", null, "1").path("nextCursor").asText();
        assertError(userId, "COMMENTED", "LATEST", cursor, "1", 400, "VALIDATION_FAILED");
        assertError(userId, "ALL", "OLDEST", cursor, "1", 400, "VALIDATION_FAILED");
        assertError(userId, null, "LATEST", cursor, "1", 400, "VALIDATION_FAILED");
    }

    @Test
    void noToken() throws Exception {
        MvcResult response =
                mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andReturn();
        assertThat(
                        objectMapper
                                .readTree(response.getResponse().getContentAsString())
                                .path("code")
                                .asText())
                .isEqualTo("UNAUTHORIZED");
    }

    @Test
    void invalidExpiredAndRefreshToken() throws Exception {
        assertUnauthorized("Bearer broken.token", "UNAUTHORIZED");
        assertUnauthorized("Basic abc", "UNAUTHORIZED");
        assertUnauthorized(
                "Bearer " + tokenProvider.createRefreshToken(userId, "my-rating-refresh"),
                "UNAUTHORIZED");
        JjwtTokenProvider expiring =
                new JjwtTokenProvider(
                        new JwtProperties(
                                jwtProperties.secret(),
                                Duration.ofSeconds(-1),
                                jwtProperties.refreshTokenTtl(),
                                jwtProperties.signupTokenTtl(),
                                jwtProperties.refreshRotationGrace(),
                                jwtProperties.refreshCookie()));
        assertUnauthorized("Bearer " + expiring.createAccessToken(userId), "TOKEN_EXPIRED");
    }

    @Test
    void inactiveUser() throws Exception {
        jdbc.sql("UPDATE users SET status = 'WITHDRAWN' WHERE id = :id")
                .param("id", userId)
                .update();
        assertError(userId, null, null, null, null, 401, "UNAUTHORIZED");
    }

    @Test
    void withdrawalCommittedBeforePageSelectIsUnauthorized() throws Exception {
        long movie = insertMovie("탈퇴 경쟁", null);
        insertRating(userId, movie, "4.0", null, null, false, BASE);
        CountDownLatch beforeSelect = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        MyRatingQueryRepository target = AopTestUtils.getUltimateTargetObject(queryRepository);
        Field jdbcField = MyRatingQueryRepository.class.getDeclaredField("jdbc");
        jdbcField.setAccessible(true);
        JdbcClient original = (JdbcClient) jdbcField.get(target);
        JdbcClient delayed =
                (JdbcClient)
                        Proxy.newProxyInstance(
                                JdbcClient.class.getClassLoader(),
                                new Class<?>[] {JdbcClient.class},
                                (proxy, method, arguments) -> {
                                    if (method.getName().equals("sql")
                                            && arguments[0] instanceof String sql
                                            && sql.contains("WITH access AS")) {
                                        beforeSelect.countDown();
                                        if (!proceed.await(10, TimeUnit.SECONDS)) {
                                            throw new AssertionError(
                                                    "page SELECT was not released");
                                        }
                                    }
                                    try {
                                        return method.invoke(original, arguments);
                                    } catch (InvocationTargetException exception) {
                                        throw exception.getCause();
                                    }
                                });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        jdbcField.set(target, delayed);
        try {
            Future<MvcResult> response =
                    worker.submit(() -> request(userId, null, null, null, null, 401));
            assertThat(beforeSelect.await(10, TimeUnit.SECONDS)).isTrue();
            jdbc.sql("UPDATE users SET status = 'WITHDRAWN' WHERE id = :id")
                    .param("id", userId)
                    .update();
            proceed.countDown();
            JsonNode body =
                    objectMapper.readTree(
                            response.get(10, TimeUnit.SECONDS).getResponse().getContentAsString());
            assertThat(body.path("code").asText()).isEqualTo("UNAUTHORIZED");
        } finally {
            proceed.countDown();
            jdbcField.set(target, original);
            worker.shutdownNow();
        }
    }

    @Test
    void queryFailureReturnsSanitizedError() throws Exception {
        MyRatingQueryRepository target = AopTestUtils.getUltimateTargetObject(queryRepository);
        Field jdbcField = MyRatingQueryRepository.class.getDeclaredField("jdbc");
        jdbcField.setAccessible(true);
        JdbcClient original = (JdbcClient) jdbcField.get(target);
        JdbcClient failing =
                (JdbcClient)
                        Proxy.newProxyInstance(
                                JdbcClient.class.getClassLoader(),
                                new Class<?>[] {JdbcClient.class},
                                (proxy, method, arguments) -> {
                                    if (method.getName().equals("sql")
                                            && arguments[0] instanceof String sql
                                            && sql.contains("WITH access AS")) {
                                        throw new DataAccessResourceFailureException(
                                                "private SQL: SELECT * FROM ratings");
                                    }
                                    try {
                                        return method.invoke(original, arguments);
                                    } catch (InvocationTargetException exception) {
                                        throw exception.getCause();
                                    }
                                });
        jdbcField.set(target, failing);
        try {
            JsonNode response =
                    objectMapper.readTree(
                            request(userId, null, null, null, null, 500)
                                    .getResponse()
                                    .getContentAsString());
            assertThat(response.path("code").asText()).isEqualTo("INTERNAL_ERROR");
            assertThat(response.path("message").asText()).isNotBlank();
            assertThat(response.toString()).doesNotContain("SELECT", "ratings", "DataAccess");
        } finally {
            jdbcField.set(target, original);
        }
    }

    @Test
    void commentFilter() throws Exception {
        long written = insertMovie("한줄평", null);
        insertRating(userId, written, "4.0", "내용", "PRIVATE", true, BASE);
        insertRating(userId, insertMovie("점수만", null), "3.0", null, null, false, BASE);
        insertRating(userId, insertMovie("공백", null), "2.0", "\t\u3000", "PUBLIC", false, BASE);
        JsonNode items = ok(userId, "COMMENTED", null, null, null).path("items");
        assertThat(movieIds(items)).containsExactly(written);
        assertThat(items.get(0).path("spoiler").asBoolean()).isTrue();
    }

    @Test
    void allUnionsRatedAndUnrated() throws Exception {
        long rated = insertMovie("평가", null);
        long unrated = insertMovie("미평가", null);
        long clicked = insertMovie("클릭만", null);
        long expired = insertMovie("만료", null);
        insertRating(userId, rated, "4.5", null, null, false, BASE);
        insertWatch(userId, unrated, "WATCHED_UNRATED", BASE.plusSeconds(1));
        insertWatch(userId, clicked, "LINK_CLICKED", BASE.plusSeconds(2));
        insertWatch(userId, expired, "EXPIRED", BASE.plusSeconds(3));
        JsonNode items = ok(userId, "ALL", null, null, null).path("items");
        assertThat(movieIds(items)).containsExactly(unrated, rated);
        JsonNode un = items.get(0);
        assertThat(un.path("itemType").asText()).isEqualTo("UNRATED");
        for (String field :
                List.of(
                        "ratingId",
                        "score",
                        "comment",
                        "visibility",
                        "spoiler",
                        "frameId",
                        "createdAt")) {
            assertThat(un.path(field).isNull()).as(field).isTrue();
        }
    }

    @Test
    void mixedKeysetPages() throws Exception {
        long low = insertMovie("저점", null);
        long mid = insertMovie("중간", null);
        long high = insertMovie("고점", null);
        long unFirst = insertMovie("미평가 먼저", null);
        long unSecond = insertMovie("미평가 나중", null);
        insertRating(userId, low, "1.0", "저점 내용", "PUBLIC", false, BASE);
        insertRating(userId, mid, "3.0", "중간 내용", "PUBLIC", false, BASE.plusSeconds(1));
        insertRating(userId, high, "5.0", "고점 내용", "PUBLIC", false, BASE.plusSeconds(2));
        insertWatch(userId, unFirst, "WATCHED_UNRATED", BASE.plusSeconds(3));
        insertWatch(userId, unSecond, "WATCHED_UNRATED", BASE.plusSeconds(4));
        assertThat(allPages(userId, "ALL", "LATEST", 2))
                .containsExactly(unSecond, unFirst, high, mid, low);
        assertThat(allPages(userId, "ALL", "OLDEST", 2))
                .containsExactly(low, mid, high, unFirst, unSecond);
        assertThat(allPages(userId, "ALL", "SCORE_DESC", 2))
                .containsExactly(high, mid, low, unSecond, unFirst);
        assertThat(allPages(userId, "ALL", "SCORE_ASC", 2))
                .containsExactly(low, mid, high, unSecond, unFirst);
    }

    @Test
    void unratedUsesWatchStatusAndFallback() throws Exception {
        long confirmed = insertMovie("확인", null);
        long deletedRating = insertMovie("삭제 후", null);
        long clicked = insertMovie("클릭만", null);
        long expired = insertMovie("만료", null);
        long otherOwned = insertMovie("타인 미평가", null);
        insertWatch(userId, confirmed, "WATCHED_UNRATED", BASE);
        long deletedWatch =
                insertWatch(userId, deletedRating, "WATCHED_UNRATED", BASE.minusSeconds(1));
        jdbc.sql("UPDATE watch_records SET watched_at = NULL, rated_at = :time WHERE id = :id")
                .param("time", utc(BASE.plusSeconds(1)))
                .param("id", deletedWatch)
                .update();
        insertWatch(userId, clicked, "LINK_CLICKED", BASE.plusSeconds(2));
        insertWatch(userId, expired, "EXPIRED", BASE.plusSeconds(3));
        insertWatch(otherUserId, otherOwned, "WATCHED_UNRATED", BASE.plusSeconds(4));
        assertThat(movieIds(ok(userId, "UNRATED", null, null, null).path("items")))
                .containsExactly(deletedRating, confirmed);
    }

    @Test
    void scoreTiesAndTimestampTiesAreStable() throws Exception {
        long first = insertMovie("평가1", null);
        long second = insertMovie("평가2", null);
        long un = insertMovie("미평가", null);
        insertRating(userId, first, "4.5", null, null, false, BASE);
        insertRating(userId, second, "4.5", null, null, false, BASE);
        insertWatch(userId, un, "WATCHED_UNRATED", BASE);
        assertThat(allPages(userId, "ALL", "LATEST", 1)).containsExactly(second, first, un);
        assertThat(allPages(userId, "ALL", "SCORE_DESC", 1)).containsExactly(second, first, un);
        assertThat(allPages(userId, "ALL", "SCORE_ASC", 1)).containsExactly(second, first, un);
    }

    @Test
    void nullActivityAtIsLastAndCursorStable() throws Exception {
        long known = insertMovie("확인 시각", null);
        long unknown = insertMovie("직접 평가 삭제 후", null);
        insertWatch(userId, known, "WATCHED_UNRATED", BASE);
        long watch = insertWatch(userId, unknown, "WATCHED_UNRATED", BASE.minusSeconds(1));
        jdbc.sql("UPDATE watch_records SET watched_at = NULL, clicked_at = NULL WHERE id = :id")
                .param("id", watch)
                .update();
        assertThat(allPages(userId, "UNRATED", "LATEST", 1)).containsExactly(known, unknown);
        assertThat(allPages(userId, "UNRATED", "OLDEST", 1)).containsExactly(known, unknown);
        assertThat(allPages(userId, "UNRATED", "SCORE_DESC", 1)).containsExactly(known, unknown);
    }

    @Test
    void newCombinedViewMatchesLegacySubsets() throws Exception {
        long rated = insertMovie("평가", null);
        long unrated = insertMovie("미평가", null);
        insertRating(userId, rated, "4.5", "내 한줄평", "PUBLIC", false, BASE);
        insertWatch(userId, unrated, "WATCHED_UNRATED", BASE.plusSeconds(1));
        assertThat(movieIds(ok(userId, null, null, null, null).path("items")))
                .containsExactly(rated);
        assertThat(movieIds(ok(userId, "ALL", null, null, null).path("items")))
                .containsExactly(unrated, rated);
        assertThat(movieIds(ok(userId, "COMMENTED", null, null, null).path("items")))
                .containsExactly(rated);
        assertThat(movieIds(ok(userId, "UNRATED", null, null, null).path("items")))
                .containsExactly(unrated);
    }

    @Test
    void ratingWritersAreVisibleWithoutCaching() throws Exception {
        long movie = insertMovie("실제 생성 후 조회", null);
        JsonNode created =
                objectMapper.readTree(
                        mockMvc.perform(
                                        post("/api/v1/movies/{movieId}/ratings", movie)
                                                .header(
                                                        "Authorization",
                                                        "Bearer "
                                                                + tokenProvider.createAccessToken(
                                                                        userId))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"score\":4.5,\"comment\":\"내 스포\",\"visibility\":\"PRIVATE\",\"spoiler\":true}"))
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        JsonNode listed = ok(userId, null, null, null, null).path("items").get(0);
        assertThat(listed.path("ratingId").asLong()).isEqualTo(created.path("ratingId").asLong());
        assertThat(listed.path("frameId").asLong())
                .isEqualTo(created.path("frame").path("frameId").asLong());
        assertThat(listed.path("score").decimalValue()).isEqualByComparingTo("4.5");
        assertThat(listed.path("comment").asText()).isEqualTo("내 스포");
        assertThat(listed.path("visibility").asText()).isEqualTo("PRIVATE");
        assertThat(listed.path("spoiler").asBoolean()).isTrue();

        long ratingId = created.path("ratingId").asLong();
        String authorization = "Bearer " + tokenProvider.createAccessToken(userId);
        mockMvc.perform(
                        put("/api/v1/ratings/{ratingId}", ratingId)
                                .header("Authorization", authorization)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"score\":2.0,\"comment\":null,\"spoiler\":false}"))
                .andExpect(status().isOk());
        JsonNode updated = ok(userId, null, null, null, null).path("items").get(0);
        assertThat(updated.path("score").decimalValue()).isEqualByComparingTo("2.0");
        assertThat(updated.path("comment").isNull()).isTrue();
        assertThat(updated.path("spoiler").asBoolean()).isFalse();
        assertThat(ok(userId, "COMMENTED", null, null, null).path("items").size()).isZero();

        mockMvc.perform(
                        delete("/api/v1/ratings/{ratingId}", ratingId)
                                .header("Authorization", authorization))
                .andExpect(status().isNoContent());
        assertThat(ok(userId, null, null, null, null).path("items").size()).isZero();
        assertThat(ok(userId, "COMMENTED", null, null, null).path("items").size()).isZero();
        JsonNode unrated = ok(userId, "UNRATED", null, null, null).path("items");
        assertThat(movieIds(unrated)).containsExactly(movie);
        assertThat(unrated.get(0).path("itemType").asText()).isEqualTo("UNRATED");
        assertThat(unrated.get(0).path("score").isNull()).isTrue();
        assertThat(movieIds(ok(userId, "ALL", null, null, null).path("items")))
                .containsExactly(movie);
    }

    private JsonNode ok(long user, String filter, String sort, String cursor, String size)
            throws Exception {
        return objectMapper.readTree(
                request(user, filter, sort, cursor, size, 200).getResponse().getContentAsString());
    }

    private void assertUnauthorized(String authorization, String expectedCode) throws Exception {
        MvcResult response =
                mockMvc.perform(get(PATH).header("Authorization", authorization))
                        .andExpect(status().isUnauthorized())
                        .andReturn();
        assertThat(
                        objectMapper
                                .readTree(response.getResponse().getContentAsString())
                                .path("code")
                                .asText())
                .isEqualTo(expectedCode);
    }

    private void assertError(
            long user,
            String filter,
            String sort,
            String cursor,
            String size,
            int status,
            String code)
            throws Exception {
        JsonNode response =
                objectMapper.readTree(
                        request(user, filter, sort, cursor, size, status)
                                .getResponse()
                                .getContentAsString());
        assertThat(response.path("code").asText()).isEqualTo(code);
        assertThat(response.path("message").asText()).isNotBlank();
    }

    private MvcResult request(
            long user, String filter, String sort, String cursor, String size, int expectedStatus)
            throws Exception {
        var request =
                get(PATH)
                        .header("Authorization", "Bearer " + tokenProvider.createAccessToken(user));
        if (filter != null) request.param("filter", filter);
        if (sort != null) request.param("sort", sort);
        if (cursor != null) request.param("cursor", cursor);
        if (size != null) request.param("size", size);
        return mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    }

    private List<Long> allPages(long user, String filter, String sort, int size) throws Exception {
        List<Long> ids = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode response = ok(user, filter, sort, cursor, Integer.toString(size));
            ids.addAll(movieIds(response.path("items")));
            cursor =
                    response.path("nextCursor").isNull()
                            ? null
                            : response.path("nextCursor").asText();
        } while (cursor != null);
        return ids;
    }

    private static List<Long> movieIds(JsonNode items) {
        List<Long> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.path("movie").path("movieId").asLong()));
        return ids;
    }

    private long insertUser(String userStatus) {
        String value = UUID.randomUUID().toString();
        return jdbc.sql(
                        """
                        INSERT INTO users(email,nickname,birth_date,gender,status)
                        VALUES (:email,:nickname,DATE '2000-01-01','OTHER',:status)
                        RETURNING id
                        """)
                .param("email", value + "@feelm.test")
                .param("nickname", value.substring(0, 10))
                .param("status", userStatus)
                .query(Long.class)
                .single();
    }

    private long insertMovie(String title, String posterUrl) {
        return jdbc.sql(
                        """
                        INSERT INTO movies(tmdb_id,title,poster_url)
                        VALUES (:tmdbId,:title,:posterUrl) RETURNING id
                        """)
                .param("tmdbId", Math.abs(UUID.randomUUID().hashCode()) + 1L)
                .param("title", title)
                .param("posterUrl", posterUrl)
                .query(Long.class)
                .single();
    }

    private long insertRating(
            long user,
            long movie,
            String score,
            String comment,
            String visibility,
            boolean spoiler,
            Instant createdAt) {
        return jdbc.sql(
                        """
                        INSERT INTO ratings(
                            user_id,movie_id,score,comment,visibility,is_spoiler,created_at,updated_at
                        ) VALUES (
                            :user,:movie,:score,:comment,:visibility,:spoiler,:createdAt,:createdAt
                        ) RETURNING id
                        """)
                .param("user", user)
                .param("movie", movie)
                .param("score", new BigDecimal(score))
                .param("comment", comment)
                .param("visibility", visibility)
                .param("spoiler", spoiler)
                .param("createdAt", utc(createdAt))
                .query(Long.class)
                .single();
    }

    private long insertWatch(long user, long movie, String watchStatus, Instant time) {
        return jdbc.sql(
                        """
                        INSERT INTO watch_records(user_id,movie_id,status,clicked_at,watched_at,rated_at)
                        VALUES (:user,:movie,:status,:clickedAt,:watchedAt,:ratedAt)
                        RETURNING id
                        """)
                .param("user", user)
                .param("movie", movie)
                .param("status", watchStatus)
                .param("clickedAt", utc(time))
                .param("watchedAt", watchStatus.equals("WATCHED_UNRATED") ? utc(time) : null)
                .param("ratedAt", (OffsetDateTime) null)
                .query(Long.class)
                .single();
    }

    private long insertFrame(long ratingId, long user, long movie) {
        return jdbc.sql(
                        """
                        INSERT INTO frames(rating_id,user_id,movie_id)
                        VALUES (:ratingId,:user,:movie) RETURNING id
                        """)
                .param("ratingId", ratingId)
                .param("user", user)
                .param("movie", movie)
                .query(Long.class)
                .single();
    }

    private static OffsetDateTime utc(Instant time) {
        return time.atOffset(ZoneOffset.UTC);
    }

    private void truncate() {
        jdbc.sql(
                        "TRUNCATE TABLE outbox_events, frames, ratings, watch_records,"
                                + " movies, popcorn_flavors, users RESTART IDENTITY CASCADE")
                .update();
    }
}
