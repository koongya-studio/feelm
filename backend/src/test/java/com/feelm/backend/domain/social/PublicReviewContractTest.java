package com.feelm.backend.domain.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.JjwtTokenProvider;
import com.feelm.backend.global.security.JwtProperties;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import com.feelm.backend.support.SharedTestRuntime;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@Import(PublicReviewQueryRecorder.Configuration.class)
@SharedTestRuntime
@DisplayName("공개 한줄평 API 계약 — API-032")
class PublicReviewContractTest extends SharedPostgresContractTest {

    private static final Instant BASE_TIME = Instant.parse("2026-09-16T01:00:00.123456Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private JdbcClient jdbc;
    @Autowired private PublicReviewQueryRecorder queryRecorder;

    private long viewerId;
    private long movieId;
    private int userSequence;

    @BeforeEach
    void setUp() {
        truncate();
        userSequence = 0;
        viewerId = insertUser("viewer", "조회자", "ACTIVE");
        movieId = insertMovie(1_610_100, "공개 한줄평 영화");
    }

    @AfterEach
    void cleanUp() {
        queryRecorder.clearPause();
        restoreRatingsTableIfNeeded();
        truncate();
    }

    @Test
    @DisplayName("T01 토큰이 없으면 공개 한줄평을 노출하지 않고 401이다")
    void requestWithoutTokenReturnsUnauthorized() throws Exception {
        long author = insertUser("author", "작성자", "ACTIVE");
        insertRating(author, movieId, "PUBLIC", "공개 한줄평", false, BASE_TIME);

        mockMvc.perform(get(path(movieId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("T02 타인의 PRIVATE 한줄평은 제외하고 PUBLIC만 반환한다")
    void memberCannotReadOthersPrivateComment() throws Exception {
        long publicAuthor = insertUser("public", "공개작성자", "ACTIVE");
        long privateAuthor = insertUser("private", "비공개작성자", "ACTIVE");
        long publicRating =
                insertRating(publicAuthor, movieId, "PUBLIC", "보이는 한줄평", false, BASE_TIME);
        insertRating(
                privateAuthor, movieId, "PRIVATE", "숨겨야 하는 한줄평", false, BASE_TIME.plusSeconds(1));

        JsonNode response = response(reviews(viewerId).andExpect(status().isOk()).andReturn());

        assertThat(ids(response)).containsExactly(publicRating);
        assertThat(response.toString()).doesNotContain("숨겨야 하는 한줄평");
    }

    @Test
    @DisplayName("T03 본인의 PRIVATE 한줄평을 mine=true로 반환한다")
    void memberReadsOwnPrivateCommentAsMine() throws Exception {
        long rating = insertRating(viewerId, movieId, "PRIVATE", "나만 보는 한줄평", false, BASE_TIME);

        JsonNode item = firstItem(response(reviews(viewerId).andReturn()));

        assertThat(item.path("ratingId").asLong()).isEqualTo(rating);
        assertThat(item.path("mine").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("T04 본인의 PUBLIC 한줄평도 mine=true로 반환한다")
    void memberPublicCommentIsAlsoMine() throws Exception {
        insertRating(viewerId, movieId, "PUBLIC", "내 공개 한줄평", false, BASE_TIME);

        JsonNode item = firstItem(response(reviews(viewerId).andReturn()));

        assertThat(item.path("mine").asBoolean()).isTrue();
        assertThat(item.path("comment").asText()).isEqualTo("내 공개 한줄평");
    }

    @Test
    @DisplayName("T05 조회자가 차단한 작성자의 PUBLIC 한줄평을 제외한다")
    void excludesUserBlockedByViewer() throws Exception {
        long author = insertUser("blocked", "차단대상", "ACTIVE");
        insertRating(author, movieId, "PUBLIC", "차단된 한줄평", false, BASE_TIME);
        insertBlock(viewerId, author);

        assertThat(ids(emptyPage(reviews(viewerId)))).isEmpty();
    }

    @Test
    @DisplayName("T06 작성자가 조회자를 차단해도 PUBLIC 한줄평을 제외한다")
    void excludesViewerBlockedByAuthor() throws Exception {
        long author = insertUser("blocker", "차단한작성자", "ACTIVE");
        insertRating(author, movieId, "PUBLIC", "역방향 차단", false, BASE_TIME);
        insertBlock(author, viewerId);

        assertThat(ids(emptyPage(reviews(viewerId)))).isEmpty();
    }

    @Test
    @DisplayName("T07 자기 차단은 DB가 거절하고 본인 PRIVATE 포함이 우선한다")
    void ownCommentWinsOverDefensiveBlockedSet() throws Exception {
        long own = insertRating(viewerId, movieId, "PRIVATE", "본인 우선", false, BASE_TIME);

        assertThatThrownBy(() -> insertBlock(viewerId, viewerId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(ids(response(reviews(viewerId).andReturn()))).containsExactly(own);
    }

    @Test
    @DisplayName("T08 comment가 null인 평가는 한줄평이 아니다")
    void ratingWithoutCommentIsNotAReview() throws Exception {
        long author = insertUser("no-comment", "평점만", "ACTIVE");
        insertRating(author, movieId, null, null, false, BASE_TIME);

        assertThat(ids(emptyPage(reviews(viewerId)))).isEmpty();
    }

    @Test
    @DisplayName("T09 사용자에게 blank로 보이는 ASCII·Unicode 공백 comment를 모두 제외한다")
    void blankCommentFollowsApprovedPolicy() throws Exception {
        List<String> blankComments =
                List.of("", "   ", "\t", "\r\n", "\u00A0", "\u3000", " \t\r\n\u00A0\u3000 ");
        for (int index = 0; index < blankComments.size(); index++) {
            insertRating(
                    insertUser("blank-" + index, "공백" + index, "ACTIVE"),
                    movieId,
                    "PUBLIC",
                    blankComments.get(index),
                    index == blankComments.size() - 1,
                    BASE_TIME.plusSeconds(index));
        }
        long visible =
                insertRating(
                        insertUser("text", "텍스트", "ACTIVE"),
                        movieId,
                        "PUBLIC",
                        "\t 실제 텍스트 \u3000",
                        false,
                        BASE_TIME.plusSeconds(blankComments.size()));

        JsonNode page = response(reviews(viewerId).andExpect(status().isOk()).andReturn());

        assertThat(ids(page)).containsExactly(visible);
        assertThat(firstItem(page).path("comment").asText()).isEqualTo("\t 실제 텍스트 \u3000");
    }

    @ParameterizedTest(name = "T47 final query 직전 차단 commit 후 비노출 — viewerBlocksAuthor={0}")
    @ValueSource(booleans = {true, false})
    void blockCommittedBeforeFinalPageQueryIsApplied(boolean viewerBlocksAuthor) throws Exception {
        long author = insertUser("race-author", "경쟁작성자", "ACTIVE");
        insertRating(author, movieId, "PUBLIC", "경쟁 차단 한줄평", false, BASE_TIME);
        PublicReviewQueryRecorder.QueryPause pause = queryRecorder.pauseBefore("FROM ratings r");
        CompletableFuture<MvcResult> request =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return reviews(viewerId).andExpect(status().isOk()).andReturn();
                            } catch (Exception exception) {
                                throw new CompletionException(exception);
                            }
                        });

        try {
            pause.awaitReached();
            if (viewerBlocksAuthor) {
                insertBlock(viewerId, author);
            } else {
                insertBlock(author, viewerId);
            }
        } finally {
            pause.release();
        }

        assertThat(ids(response(request.get(30, TimeUnit.SECONDS)))).isEmpty();
    }

    @ParameterizedTest(name = "T49 탈퇴 commit이 page SQL보다 먼저 — hasReview={0}")
    @ValueSource(booleans = {true, false})
    void withdrawalCommittedBeforePageQueryRejectsOldAccessToken(boolean hasReview)
            throws Exception {
        if (hasReview) {
            long author = insertUser("withdraw-race-author", "탈퇴경쟁작성자", "ACTIVE");
            insertRating(author, movieId, "PUBLIC", "탈퇴 뒤 노출 금지", true, BASE_TIME);
        }
        // JDBC execute 직전에 멈춘다. 기존 구현에서는 이 지점까지 별도 ACTIVE SQL이
        // 완료되고, page SELECT의 READ COMMITTED snapshot은 아직 만들어지지 않았다.
        PublicReviewQueryRecorder.QueryPause pause = queryRecorder.pauseBefore("FROM ratings r");
        CompletableFuture<MvcResult> request =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return reviews(viewerId).andReturn();
                            } catch (Exception exception) {
                                throw new CompletionException(exception);
                            }
                        });

        try {
            pause.awaitReached();
            // 별도 연결의 UPDATE가 commit된 다음에만 page SQL을 재개한다. sleep이나
            // 요청 완료 시각에 의존하지 않고, 실제 PostgreSQL의 커밋 순서를 고정한다.
            jdbc.sql("UPDATE users SET status='WITHDRAWN' WHERE id=:id")
                    .param("id", viewerId)
                    .update();
            assertThat(
                            jdbc.sql("SELECT status FROM users WHERE id=:id")
                                    .param("id", viewerId)
                                    .query(String.class)
                                    .single())
                    .isEqualTo("WITHDRAWN");
        } finally {
            pause.release();
        }

        MvcResult result = request.get(30, TimeUnit.SECONDS);
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(response(result).path("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("탈퇴 뒤 노출 금지");
    }

    @Test
    @DisplayName("T50 page SQL 완료 후 탈퇴 commit이면 해당 조회는 이전 ACTIVE snapshot으로 완료된다")
    void pageQueryBeforeWithdrawalCommitHasClearSnapshotOrder() throws Exception {
        long author = insertUser("snapshot-author", "스냅샷작성자", "ACTIVE");
        long rating = insertRating(author, movieId, "PUBLIC", "조회 먼저", false, BASE_TIME);
        PublicReviewQueryRecorder.QueryPause pause = queryRecorder.pauseAfter("FROM ratings r");
        CompletableFuture<MvcResult> request =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return reviews(viewerId).andReturn();
                            } catch (Exception exception) {
                                throw new CompletionException(exception);
                            }
                        });

        try {
            // JDBC SELECT가 반환된 다음 응답 매핑 직전에만 탈퇴를 확정한다.
            pause.awaitReached();
            jdbc.sql("UPDATE users SET status='WITHDRAWN' WHERE id=:id")
                    .param("id", viewerId)
                    .update();
            assertThat(
                            jdbc.sql("SELECT status FROM users WHERE id=:id")
                                    .param("id", viewerId)
                                    .query(String.class)
                                    .single())
                    .isEqualTo("WITHDRAWN");
        } finally {
            pause.release();
        }

        MvcResult result = request.get(30, TimeUnit.SECONDS);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(ids(response(result))).containsExactly(rating);
    }

    @Test
    @DisplayName("T48 차단 관계 66,000개에서도 parameter 한도 오류 없이 정상 page를 반환한다")
    void manyBlocksDoNotOverflowBindParameterLimit() throws Exception {
        long visibleAuthor = insertUser("bulk-visible", "대량차단외작성자", "ACTIVE");
        long visible =
                insertRating(visibleAuthor, movieId, "PUBLIC", "대량 차단 뒤에도 보임", false, BASE_TIME);
        insertBulkBlocks(66_000);

        JsonNode page = response(reviews(viewerId).andExpect(status().isOk()).andReturn());

        assertThat(ids(page)).containsExactly(visible);
        assertPageShape(page);
    }

    @Test
    @DisplayName("T10 존재하지 않는 영화는 공통 404를 반환한다")
    void missingMovieReturnsResourceNotFound() throws Exception {
        reviews(viewerId, movieId + 999)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("T11 영화가 있고 노출 행이 없으면 빈 page를 반환한다")
    void existingMovieWithoutVisibleReviewsReturnsEmptyPage() throws Exception {
        long author = insertUser("only-private", "비공개만", "ACTIVE");
        insertRating(author, movieId, "PRIVATE", "타인 비공개", false, BASE_TIME);

        JsonNode response = response(reviews(viewerId).andReturn());

        assertThat(response.path("items")).isEmpty();
        assertThat(response.path("hasNext").asBoolean()).isFalse();
        assertThat(response.path("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("T12 size 생략 시 20개와 다음 cursor를 반환한다")
    void usesDefaultPageSizeTwenty() throws Exception {
        insertReviews(21, movieId, BASE_TIME);

        JsonNode response = response(reviews(viewerId).andReturn());

        assertThat(response.path("items")).hasSize(20);
        assertThat(response.path("hasNext").asBoolean()).isTrue();
        assertThat(response.path("nextCursor").asText()).isNotBlank();
    }

    @Test
    @DisplayName("T13 size 1과 50 경계를 정확히 적용한다")
    void acceptsMinimumAndMaximumPageSize() throws Exception {
        insertReviews(51, movieId, BASE_TIME);

        JsonNode minimum = response(reviews(viewerId, movieId, "size", "1").andReturn());
        JsonNode maximum = response(reviews(viewerId, movieId, "size", "50").andReturn());

        assertThat(minimum.path("items")).hasSize(1);
        assertThat(minimum.path("hasNext").asBoolean()).isTrue();
        assertThat(maximum.path("items")).hasSize(50);
        assertThat(maximum.path("hasNext").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "51", "-1", "1.0", " ", "+1", "01", "999999999999999"})
    @DisplayName("T14 범위 밖·강제 변환 size를 HTTP 400으로 거절한다")
    void rejectsOutOfRangeOrCoercedSize(String rawSize) throws Exception {
        reviews(viewerId, movieId, "size", rawSize)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("빈 값과 중복 size도 T14 계약에 따라 HTTP 400으로 거절한다")
    void rejectsEmptyOrRepeatedSize() throws Exception {
        reviews(viewerId, movieId, "size", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(
                        get(path(movieId))
                                .header("Authorization", bearer(viewerId))
                                .queryParam("size", "1", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808", "1.0", " 1"})
    @DisplayName("T15 양의 64bit 정수가 아닌 movieId를 400으로 거절한다")
    void rejectsInvalidMovieId(String rawMovieId) throws Exception {
        mockMvc.perform(
                        get("/api/v1/movies/" + rawMovieId + "/reviews")
                                .header("Authorization", bearer(viewerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("T21 같은 microsecond 시각을 ID DESC로 누락·중복 없이 페이지 처리한다")
    void paginatesEqualMicrosecondTimestampsWithoutGapOrDuplicate() throws Exception {
        List<Long> inserted = insertReviewsAtSameTime(5, movieId, BASE_TIME);

        List<Long> actual = readAllPages(2);

        assertThat(actual).containsExactlyElementsOf(reversed(inserted));
        assertThat(actual).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("T23 실제 다음 page가 있을 때만 nextCursor를 설정한다")
    void setsNextCursorOnlyWhenNextPageExists() throws Exception {
        insertReviews(4, movieId, BASE_TIME);

        JsonNode first = response(reviews(viewerId, movieId, "size", "2").andReturn());
        JsonNode second =
                response(
                        reviews(
                                        viewerId,
                                        movieId,
                                        "size",
                                        "2",
                                        "cursor",
                                        first.path("nextCursor").asText())
                                .andReturn());

        assertThat(first.path("hasNext").asBoolean()).isTrue();
        assertThat(first.path("nextCursor").asText()).isNotBlank();
        assertThat(second.path("items")).hasSize(2);
        assertThat(second.path("hasNext").asBoolean()).isFalse();
        assertThat(second.path("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("T24 예상 밖 DB 오류에도 SQL·예외·타 사용자 정보를 노출하지 않는다")
    void doesNotExposeSqlOrExceptionNames() throws Exception {
        jdbc.sql("ALTER TABLE ratings RENAME TO ratings_unavailable_for_t24").update();
        try {
            MvcResult result =
                    reviews(viewerId)
                            .andExpect(status().isInternalServerError())
                            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                            .andReturn();
            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body)
                    .doesNotContainIgnoringCase("select")
                    .doesNotContainIgnoringCase("ratings")
                    .doesNotContain("PSQLException")
                    .doesNotContain(Long.toString(viewerId));
        } finally {
            restoreRatingsTableIfNeeded();
        }
    }

    @Test
    @DisplayName("T25 row 수와 무관하게 회원 조회 query 수가 고정되고 N+1이 없다")
    void usesBoundedQueryCountWithoutNPlusOne() throws Exception {
        List<Long> authors = insertReviews(30, movieId, BASE_TIME);
        insertBlock(viewerId, authors.getLast());
        queryRecorder.start();

        reviews(viewerId).andExpect(status().isOk());
        List<String> statements = queryRecorder.stop();

        assertThat(statements).hasSize(1);
        assertThat(
                        statements.stream()
                                .filter(
                                        sql ->
                                                sql.toUpperCase(Locale.ROOT)
                                                        .contains("FROM RATINGS"))
                                .count())
                .isOne();
        assertThat(
                        statements.stream()
                                .filter(
                                        sql ->
                                                sql.toUpperCase(Locale.ROOT)
                                                        .contains("FROM USER_BLOCKS"))
                                .count())
                .isOne();
    }

    @Test
    @DisplayName("T26 ACTIVE 작성자만 노출하고 PENDING·WITHDRAWN을 제외한다")
    void appliesApprovedUserStatusPolicy() throws Exception {
        long active = insertUser("active-author", "활성작성자", "ACTIVE");
        long pending = insertUser("pending-author", "대기작성자", "PENDING_VERIFICATION");
        long withdrawn = insertUser("withdrawn-author", "withdrawn-999", "WITHDRAWN");
        long visible = insertRating(active, movieId, "PUBLIC", "활성", false, BASE_TIME);
        insertRating(pending, movieId, "PUBLIC", "대기", false, BASE_TIME.plusSeconds(1));
        insertRating(withdrawn, movieId, "PUBLIC", "탈퇴", false, BASE_TIME.plusSeconds(2));

        JsonNode response = response(reviews(viewerId).andReturn());

        assertThat(ids(response)).containsExactly(visible);
        assertThat(response.toString()).doesNotContain("대기", "탈퇴", "withdrawn-999", "status");
        assertThat(firstItem(response).path("user").properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("userId", "nickname");
    }

    @Test
    @DisplayName("T27 토큰 없음·잘못됨·만료·refresh·다른 scheme과 비활성 회원을 거절한다")
    void memberAuthenticationRejectsInvalidTokens() throws Exception {
        mockMvc.perform(get(path(movieId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get(path(movieId)).header("Authorization", "Bearer broken.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(
                        get(path(movieId))
                                .header("Authorization", "Bearer " + expiredAccessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        mockMvc.perform(
                        get(path(movieId))
                                .header(
                                        "Authorization",
                                        "Bearer "
                                                + tokenProvider.createRefreshToken(
                                                        viewerId, "review-refresh")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get(path(movieId)).header("Authorization", "Basic abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        jdbc.sql("UPDATE users SET status='PENDING_VERIFICATION' WHERE id=:id")
                .param("id", viewerId)
                .update();
        reviews(viewerId)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        reviews(viewerId, "cursor", "malformed")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("T33 page 사이 변경은 다음 요청의 현재 차단·평가 상태를 적용한다")
    void concurrentChangesRespectDocumentedPageBoundary() throws Exception {
        long firstAuthor = insertUser("boundary-first", "첫작성자", "ACTIVE");
        long blockedAuthor = insertUser("boundary-blocked", "차단작성자", "ACTIVE");
        long remainingAuthor = insertUser("boundary-last", "남은작성자", "ACTIVE");
        long first =
                insertRating(
                        firstAuthor, movieId, "PUBLIC", "첫 페이지", false, BASE_TIME.plusSeconds(3));
        insertRating(blockedAuthor, movieId, "PUBLIC", "차단 예정", false, BASE_TIME.plusSeconds(2));
        long remaining =
                insertRating(
                        remainingAuthor,
                        movieId,
                        "PUBLIC",
                        "남은 행",
                        false,
                        BASE_TIME.plusSeconds(1));
        JsonNode page = response(reviews(viewerId, movieId, "size", "1").andReturn());

        insertBlock(viewerId, blockedAuthor);
        long newAuthor = insertUser("boundary-new", "새작성자", "ACTIVE");
        insertRating(
                newAuthor, movieId, "PUBLIC", "cursor 앞 신규 행", false, BASE_TIME.plusSeconds(4));
        JsonNode next =
                response(
                        reviews(
                                        viewerId,
                                        movieId,
                                        "size",
                                        "5",
                                        "cursor",
                                        page.path("nextCursor").asText())
                                .andReturn());

        assertThat(ids(page)).containsExactly(first);
        assertThat(ids(next)).containsExactly(remaining);
    }

    @Test
    @DisplayName("T36 PostgreSQL default false와 explicit true·false spoiler 행을 조회한다")
    void postgresReadsLegacyAndNewSpoilerRows() throws Exception {
        long defaultAuthor = insertUser("default-spoiler", "기본값", "ACTIVE");
        long trueAuthor = insertUser("true-spoiler", "트루값", "ACTIVE");
        long falseAuthor = insertUser("false-spoiler", "펄스값", "ACTIVE");
        long defaultRating =
                insertRatingWithDefaultSpoiler(
                        defaultAuthor, movieId, "기본 false", BASE_TIME.plusSeconds(2));
        long trueRating =
                insertRating(
                        trueAuthor,
                        movieId,
                        "PUBLIC",
                        "explicit true",
                        true,
                        BASE_TIME.plusSeconds(1));
        long falseRating =
                insertRating(falseAuthor, movieId, "PUBLIC", "explicit false", false, BASE_TIME);

        Map<Long, Boolean> spoilers = spoilers(response(reviews(viewerId).andReturn()));

        assertThat(spoilers)
                .containsEntry(defaultRating, false)
                .containsEntry(trueRating, true)
                .containsEntry(falseRating, false);
    }

    @Test
    @DisplayName("T37 타인의 PUBLIC spoiler는 원문과 true를 반환한다")
    void publicSpoilerReturnsRawCommentAndTrue() throws Exception {
        long author = insertUser("raw-spoiler", "스포작성자", "ACTIVE");
        insertRating(author, movieId, "PUBLIC", "범인은 원문", true, BASE_TIME);

        JsonNode item = firstItem(response(reviews(viewerId).andReturn()));

        assertThat(item.path("comment").asText()).isEqualTo("범인은 원문");
        assertThat(item.path("spoiler").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("T38 일반 한줄평도 spoiler=false key를 생략하지 않는다")
    void ordinaryReviewReturnsSpoilerFalse() throws Exception {
        long author = insertUser("ordinary", "일반작성자", "ACTIVE");
        insertRating(author, movieId, "PUBLIC", "일반 한줄평", false, BASE_TIME);

        JsonNode item = firstItem(response(reviews(viewerId).andReturn()));

        assertThat(item.has("spoiler")).isTrue();
        assertThat(item.path("spoiler").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("T39 본인 PRIVATE spoiler는 mine=true와 spoiler=true다")
    void ownSpoilerReturnsMineAndSpoilerTrue() throws Exception {
        insertRating(viewerId, movieId, "PRIVATE", "내 스포일러", true, BASE_TIME);

        JsonNode item = firstItem(response(reviews(viewerId).andReturn()));

        assertThat(item.path("mine").asBoolean()).isTrue();
        assertThat(item.path("spoiler").asBoolean()).isTrue();
        assertThat(item.path("comment").asText()).isEqualTo("내 스포일러");
    }

    @Test
    @DisplayName("T40 타인의 PUBLIC spoiler는 mine=false와 spoiler=true다")
    void othersSpoilerReturnsMineFalseAndSpoilerTrue() throws Exception {
        long author = insertUser("other-spoiler", "타인스포", "ACTIVE");
        insertRating(author, movieId, "PUBLIC", "타인 스포일러", true, BASE_TIME);

        JsonNode item = firstItem(response(reviews(viewerId).andReturn()));

        assertThat(item.path("mine").asBoolean()).isFalse();
        assertThat(item.path("spoiler").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("T41 spoiler 값은 공개 범위·소유권·차단 filter를 바꾸지 않는다")
    void spoilerDoesNotChangeVisibilityOwnershipOrBlockFilters() throws Exception {
        long publicTrueAuthor = insertUser("matrix-true", "공개트루", "ACTIVE");
        long publicFalseAuthor = insertUser("matrix-false", "공개펄스", "ACTIVE");
        long privateAuthor = insertUser("matrix-private", "비공개타인", "ACTIVE");
        long blockedAuthor = insertUser("matrix-blocked", "차단타인", "ACTIVE");
        long publicTrue =
                insertRating(
                        publicTrueAuthor,
                        movieId,
                        "PUBLIC",
                        "공개 true",
                        true,
                        BASE_TIME.plusSeconds(4));
        long publicFalse =
                insertRating(
                        publicFalseAuthor,
                        movieId,
                        "PUBLIC",
                        "공개 false",
                        false,
                        BASE_TIME.plusSeconds(3));
        insertRating(
                privateAuthor,
                movieId,
                "PRIVATE",
                "타인 private true",
                true,
                BASE_TIME.plusSeconds(2));
        insertRating(
                blockedAuthor,
                movieId,
                "PUBLIC",
                "차단 public false",
                false,
                BASE_TIME.plusSeconds(1));
        long own = insertRating(viewerId, movieId, "PRIVATE", "본인 true", true, BASE_TIME);
        insertBlock(blockedAuthor, viewerId);

        JsonNode response = response(reviews(viewerId).andReturn());

        assertThat(ids(response)).containsExactly(publicTrue, publicFalse, own);
        assertThat(spoilers(response))
                .containsEntry(publicTrue, true)
                .containsEntry(publicFalse, false)
                .containsEntry(own, true);
    }

    @Test
    @DisplayName("T42 다음 page에서도 rating별 spoiler 값을 보존한다")
    void nextPagePreservesSpoilerValues() throws Exception {
        List<Long> ids = new ArrayList<>();
        Map<Long, Boolean> expected = new LinkedHashMap<>();
        for (int index = 0; index < 4; index++) {
            long author = insertUser("page-spoiler-" + index, "페이지" + index, "ACTIVE");
            boolean spoiler = index % 2 == 0;
            long id =
                    insertRating(
                            author,
                            movieId,
                            "PUBLIC",
                            "페이지 spoiler " + index,
                            spoiler,
                            BASE_TIME.minusSeconds(index));
            ids.add(id);
            expected.put(id, spoiler);
        }

        JsonNode first = response(reviews(viewerId, movieId, "size", "2").andReturn());
        JsonNode second =
                response(
                        reviews(
                                        viewerId,
                                        movieId,
                                        "size",
                                        "2",
                                        "cursor",
                                        first.path("nextCursor").asText())
                                .andReturn());
        Map<Long, Boolean> actual = new LinkedHashMap<>();
        actual.putAll(spoilers(first));
        actual.putAll(spoilers(second));

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    @DisplayName("T43 같은 microsecond의 mixed spoiler를 ID DESC로 보존한다")
    void equalTimestampPaginationPreservesMixedSpoilers() throws Exception {
        List<Long> inserted = new ArrayList<>();
        Map<Long, Boolean> expected = new LinkedHashMap<>();
        for (int index = 0; index < 5; index++) {
            long author = insertUser("tie-spoiler-" + index, "동시각" + index, "ACTIVE");
            boolean spoiler = index % 2 == 1;
            long id = insertRating(author, movieId, "PUBLIC", "동시각 " + index, spoiler, BASE_TIME);
            inserted.add(id);
            expected.put(id, spoiler);
        }

        List<JsonNode> pages = readAllPageDocuments(2);
        List<Long> actualIds = pages.stream().flatMap(page -> ids(page).stream()).toList();
        Map<Long, Boolean> actualSpoilers = new LinkedHashMap<>();
        pages.forEach(page -> actualSpoilers.putAll(spoilers(page)));

        assertThat(actualIds).containsExactlyElementsOf(reversed(inserted));
        assertThat(actualIds).doesNotHaveDuplicates();
        assertThat(actualSpoilers).containsExactlyInAnyOrderEntriesOf(expected);
    }

    private ResultActions reviews(long userId, String... parameters) throws Exception {
        return reviews(userId, movieId, parameters);
    }

    private ResultActions reviews(long userId, long targetMovieId, String... parameters)
            throws Exception {
        MockHttpServletRequestBuilder request =
                get(path(targetMovieId)).header("Authorization", bearer(userId));
        for (int index = 0; index < parameters.length; index += 2) {
            request.queryParam(parameters[index], parameters[index + 1]);
        }
        return mockMvc.perform(request);
    }

    private JsonNode response(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode emptyPage(ResultActions actions) throws Exception {
        JsonNode page = response(actions.andExpect(status().isOk()).andReturn());
        assertPageShape(page);
        assertThat(page.path("items")).isEmpty();
        assertThat(page.path("hasNext").asBoolean()).isFalse();
        assertThat(page.path("nextCursor").isNull()).isTrue();
        return page;
    }

    private static void assertPageShape(JsonNode page) {
        assertThat(page.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("items", "nextCursor", "hasNext");
        assertThat(page.path("items").isArray()).isTrue();
        assertThat(page.path("hasNext").isBoolean()).isTrue();
        assertThat(page.has("nextCursor")).isTrue();
    }

    private static JsonNode firstItem(JsonNode response) {
        return response.path("items").get(0);
    }

    private static List<Long> ids(JsonNode response) {
        List<Long> ids = new ArrayList<>();
        response.path("items").forEach(item -> ids.add(item.path("ratingId").asLong()));
        return ids;
    }

    private static Map<Long, Boolean> spoilers(JsonNode response) {
        Map<Long, Boolean> values = new LinkedHashMap<>();
        response.path("items")
                .forEach(
                        item ->
                                values.put(
                                        item.path("ratingId").asLong(),
                                        item.path("spoiler").asBoolean()));
        return values;
    }

    private List<Long> readAllPages(int size) throws Exception {
        return readAllPageDocuments(size).stream().flatMap(page -> ids(page).stream()).toList();
    }

    private List<JsonNode> readAllPageDocuments(int size) throws Exception {
        List<JsonNode> pages = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page =
                    cursor == null
                            ? response(
                                    reviews(viewerId, movieId, "size", Integer.toString(size))
                                            .andReturn())
                            : response(
                                    reviews(
                                                    viewerId,
                                                    movieId,
                                                    "size",
                                                    Integer.toString(size),
                                                    "cursor",
                                                    cursor)
                                            .andReturn());
            pages.add(page);
            cursor = page.path("hasNext").asBoolean() ? page.path("nextCursor").asText() : null;
        } while (cursor != null);
        return pages;
    }

    private List<Long> insertReviews(int count, long targetMovieId, Instant newest) {
        List<Long> authors = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            long author = insertUser("bulk-" + userSequence, "대량" + userSequence, "ACTIVE");
            userSequence++;
            insertRating(
                    author,
                    targetMovieId,
                    "PUBLIC",
                    "대량 한줄평 " + index,
                    index % 2 == 0,
                    newest.minusSeconds(index));
            authors.add(author);
        }
        return authors;
    }

    private List<Long> insertReviewsAtSameTime(int count, long targetMovieId, Instant time) {
        List<Long> ratings = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            long author = insertUser("tie-" + index, "동일시각" + index, "ACTIVE");
            ratings.add(
                    insertRating(
                            author,
                            targetMovieId,
                            "PUBLIC",
                            "동일 시각 " + index,
                            index % 2 == 0,
                            time));
        }
        return ratings;
    }

    private long insertUser(String key, String nickname, String status) {
        return jdbc.sql(
                        """
                        INSERT INTO users(email,nickname,birth_date,gender,status)
                        VALUES (:email,:nickname,DATE '1994-04-04','OTHER',:status)
                        RETURNING id
                        """)
                .param("email", key + "-" + userSequence++ + "@feelm.test")
                .param("nickname", nickname + userSequence)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private long insertMovie(int tmdbId, String title) {
        return jdbc.sql("INSERT INTO movies(tmdb_id,title) VALUES (:tmdb,:title) RETURNING id")
                .param("tmdb", tmdbId)
                .param("title", title)
                .query(Long.class)
                .single();
    }

    private long insertRating(
            long author,
            long targetMovie,
            String visibility,
            String comment,
            boolean spoiler,
            Instant createdAt) {
        return jdbc.sql(
                        """
                        INSERT INTO ratings(
                            user_id,movie_id,score,comment,visibility,is_spoiler,
                            created_at,updated_at
                        ) VALUES (
                            :author,:movie,4.5,:comment,:visibility,:spoiler,:created,:created
                        ) RETURNING id
                        """)
                .param("author", author)
                .param("movie", targetMovie)
                .param("comment", comment)
                .param("visibility", visibility)
                .param("spoiler", spoiler)
                .param("created", createdAt.atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    private long insertRatingWithDefaultSpoiler(
            long author, long targetMovie, String comment, Instant createdAt) {
        return jdbc.sql(
                        """
                        INSERT INTO ratings(
                            user_id,movie_id,score,comment,visibility,created_at,updated_at
                        ) VALUES (
                            :author,:movie,4.5,:comment,'PUBLIC',:created,:created
                        ) RETURNING id
                        """)
                .param("author", author)
                .param("movie", targetMovie)
                .param("comment", comment)
                .param("created", createdAt.atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    private void insertBlock(long blocker, long blocked) {
        jdbc.sql("INSERT INTO user_blocks(blocker_id,blocked_id) VALUES (:blocker,:blocked)")
                .param("blocker", blocker)
                .param("blocked", blocked)
                .update();
    }

    private void insertBulkBlocks(int count) {
        jdbc.sql(
                        """
                        WITH inserted AS (
                            INSERT INTO users(email,nickname,birth_date,gender,status)
                            SELECT 'bulk-block-' || value || '@feelm.test',
                                   '대량차단' || value,
                                   DATE '1994-04-04', 'OTHER', 'ACTIVE'
                            FROM generate_series(1, :count) value
                            RETURNING id
                        )
                        INSERT INTO user_blocks(blocker_id, blocked_id)
                        SELECT :viewerId, id FROM inserted
                        """)
                .param("count", count)
                .param("viewerId", viewerId)
                .update();
    }

    private String bearer(long userId) {
        return "Bearer " + tokenProvider.createAccessToken(userId);
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
        return expiring.createAccessToken(viewerId);
    }

    private static String path(long targetMovieId) {
        return "/api/v1/movies/" + targetMovieId + "/reviews";
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE users, movies RESTART IDENTITY CASCADE").update();
    }

    private void restoreRatingsTableIfNeeded() {
        Boolean hidden =
                jdbc.sql("SELECT to_regclass('public.ratings_unavailable_for_t24') IS NOT NULL")
                        .query(Boolean.class)
                        .single();
        if (Boolean.TRUE.equals(hidden)) {
            jdbc.sql("ALTER TABLE ratings_unavailable_for_t24 RENAME TO ratings").update();
        }
    }

    private static List<Long> reversed(List<Long> source) {
        List<Long> reversed = new ArrayList<>(source);
        java.util.Collections.reverse(reversed);
        return reversed;
    }
}
