package com.feelm.backend.domain.social;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import com.feelm.backend.support.SharedTestRuntime;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * API-062 공개 프로필 조회 계약 테스트.
 *
 * <p>실제 스키마를 Flyway로 올린 PostgreSQL에서 확인한다. {@code users.public_id}의 기본값, 팝콘 맛 기준정보 시드, {@code
 * user_blocks}의 양방향 판정이 모두 마이그레이션에 있어 손으로 만든 스키마로는 같은 것을 확인할 수 없다.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@SharedTestRuntime
@DisplayName("공개 프로필 API 계약 — API-062")
class PublicProfileContractTest extends SharedPostgresContractTest {

    private static final Instant BASE_TIME = Instant.parse("2026-09-20T01:00:00Z");
    private static final String FIRST_FLAVOR_ID =
            "SELECT id FROM popcorn_flavors ORDER BY sort_order LIMIT 1";
    private static final String FIRST_FLAVOR_CODE =
            "SELECT code FROM popcorn_flavors ORDER BY sort_order LIMIT 1";

    @Autowired private MockMvc mockMvc;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private JdbcClient jdbc;

    private int sequence;
    private long ownerId;
    private String ownerPublicId;
    private String ownerNickname;
    private long firstFlavorId;
    private String firstFlavorCode;

    @BeforeEach
    void setUp() {
        truncate();
        sequence = 0;
        ownerId = insertUser("owner", "무비버디", "ACTIVE", "PUBLIC");
        ownerPublicId = publicIdOf(ownerId);
        ownerNickname = nicknameOf(ownerId);
        firstFlavorId = jdbc.sql(FIRST_FLAVOR_ID).query(Long.class).single();
        firstFlavorCode = jdbc.sql(FIRST_FLAVOR_CODE).query(String.class).single();
    }

    @AfterEach
    void cleanUp() {
        truncate();
    }

    @Test
    @DisplayName("T01 비회원이 PUBLIC 사용자를 조회하면 계약의 응답 모양 그대로 200이다")
    void anonymousReadsPublicProfile() throws Exception {
        jdbc.sql("UPDATE users SET avatar_storage_key = :key WHERE id = :id")
                .param("key", "0b649505-0917-41d9-a41f-0d4402ec63d5.jpg")
                .param("id", ownerId)
                .update();
        long movieId = insertMovie("파이트 클럽", "https://image.test/poster.jpg", firstFlavorId);
        long ratingId = insertRating(ownerId, movieId, "4.5", "인생 영화", "PUBLIC", BASE_TIME);
        long frameId = insertFrame(ratingId, ownerId, movieId, BASE_TIME);
        insertTasteAnalysis(ownerId, "DARK_THRILLER_LOVER", "어둠 속 스릴러 애호가");

        mockMvc.perform(path(ownerPublicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.userId").value(ownerId))
                .andExpect(jsonPath("$.user.nickname").value(ownerNickname))
                .andExpect(
                        jsonPath("$.user.avatarUrl")
                                .value("/media/avatars/0b649505-0917-41d9-a41f-0d4402ec63d5.jpg"))
                .andExpect(jsonPath("$.user.visibility").value("PUBLIC"))
                // 공개 프로필은 계정 식별 정보를 더 내려보내지 않는다.
                .andExpect(jsonPath("$.user.email").doesNotExist())
                .andExpect(jsonPath("$.user.birthDate").doesNotExist())
                .andExpect(jsonPath("$.keywords[0].code").value("DARK_THRILLER_LOVER"))
                .andExpect(jsonPath("$.keywords[0].label").value("어둠 속 스릴러 애호가"))
                .andExpect(jsonPath("$.stats.frameCount").value(1))
                .andExpect(jsonPath("$.stats.popcornCount").value(1))
                .andExpect(jsonPath("$.popcorn.flavors[0].flavorCode").value(firstFlavorCode))
                .andExpect(jsonPath("$.popcorn.flavors[0].popcornCount").value(1))
                .andExpect(jsonPath("$.popcorn.flavors[0].averageScore").value(4.5))
                .andExpect(jsonPath("$.recentFrames[0].frameId").value(frameId))
                .andExpect(jsonPath("$.recentFrames[0].movie.movieId").value(movieId))
                .andExpect(jsonPath("$.recentFrames[0].movie.title").value("파이트 클럽"))
                .andExpect(
                        jsonPath("$.recentFrames[0].movie.posterUrl")
                                .value("https://image.test/poster.jpg"))
                .andExpect(jsonPath("$.recentFrames[0].score").value(4.5))
                .andExpect(jsonPath("$.recentFrames[0].createdAt").exists())
                .andExpect(jsonPath("$.publicReviewCount").value(1))
                .andExpect(jsonPath("$.isBlockedByMe").value(false));
    }

    @Test
    @DisplayName("T02 없는 publicId는 404 RESOURCE_NOT_FOUND다")
    void unknownPublicIdReturnsNotFound() throws Exception {
        mockMvc.perform(path("1b4e28ba-2fa1-11d2-883f-0016d3cca427"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /**
     * {@code 1-1-1-1-1}은 {@code UUID.fromString}이 받아들여 {@code 0000...0001}로 접히는 값이다(리뷰 B-5). 파싱에서
     * 걸리는 값과 파싱을 통과한 뒤 조회에서 비는 값이 같은 404가 되는지 함께 본다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"1", "not-a-uuid", "1b4e28ba-2fa1-11d2-883f", "1-1-1-1-1"})
    @DisplayName("T03 UUID가 아니거나 비어 있는 publicId도 없는 사용자와 같은 404다")
    void malformedPublicIdReturnsNotFound(String publicId) throws Exception {
        mockMvc.perform(path(publicId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("T04 PRIVATE 사용자는 타인에게도 비회원에게도 404다")
    void privateProfileIsHiddenFromOthers() throws Exception {
        long privateUser = insertUser("private", "비공개", "ACTIVE", "PRIVATE");
        long viewer = insertUser("viewer", "조회자", "ACTIVE", "PUBLIC");

        mockMvc.perform(path(publicIdOf(privateUser)).header("Authorization", bearer(viewer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(path(publicIdOf(privateUser))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("T05 본인 조회는 PRIVATE이어도 200이다")
    void ownerReadsOwnPrivateProfile() throws Exception {
        long privateUser = insertUser("private", "비공개", "ACTIVE", "PRIVATE");

        mockMvc.perform(path(publicIdOf(privateUser)).header("Authorization", bearer(privateUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.userId").value(privateUser))
                .andExpect(jsonPath("$.user.visibility").value("PRIVATE"));
    }

    @Test
    @DisplayName("T06 내가 차단한 상대의 공개 프로필은 404다")
    void blockedByViewerReturnsNotFound() throws Exception {
        long viewer = insertUser("viewer", "조회자", "ACTIVE", "PUBLIC");
        insertBlock(viewer, ownerId);

        mockMvc.perform(path(ownerPublicId).header("Authorization", bearer(viewer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("T07 나를 차단한 상대의 공개 프로필도 404다")
    void blockedByTargetReturnsNotFound() throws Exception {
        long viewer = insertUser("viewer", "조회자", "ACTIVE", "PUBLIC");
        insertBlock(ownerId, viewer);

        mockMvc.perform(path(ownerPublicId).header("Authorization", bearer(viewer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("T08 PRIVATE 한줄평과 공백 한줄평은 공개 평가 수에서 빠진다")
    void privateAndBlankReviewsAreExcludedFromCount() throws Exception {
        long publicMovie = insertMovie("공개 한줄평 영화", null, firstFlavorId);
        long privateMovie = insertMovie("비공개 한줄평 영화", null, firstFlavorId);
        long blankMovie = insertMovie("공백 한줄평 영화", null, firstFlavorId);
        long noCommentMovie = insertMovie("한줄평 없는 영화", null, firstFlavorId);
        insertRating(ownerId, publicMovie, "4.0", "공개", "PUBLIC", BASE_TIME);
        insertRating(ownerId, privateMovie, "4.0", "비공개", "PRIVATE", BASE_TIME);
        insertRating(ownerId, blankMovie, "4.0", "   ", "PUBLIC", BASE_TIME);
        insertRating(ownerId, noCommentMovie, "4.0", null, null, BASE_TIME);

        mockMvc.perform(path(ownerPublicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicReviewCount").value(1))
                // 평가 자체는 팝콘 집계에 그대로 들어간다. 빠지는 것은 한줄평 수뿐이다.
                .andExpect(jsonPath("$.stats.popcornCount").value(4));
    }

    /**
     * 활성이 아닌 계정은 없는 사용자와 같다(리뷰 A-5).
     *
     * <p>리뷰는 {@code PENDING_VERIFICATION}도 함께 보라고 했으나, 그 상태는 {@code
     * V202609181430__remove_email_link_verification}이 {@code ck_users_status}에서 지웠다. 지금 활성이 아닌 계정은
     * 탈퇴뿐이다.
     */
    @Test
    @DisplayName("T09 탈퇴한 계정은 404다")
    void withdrawnUserReturnsNotFound() throws Exception {
        long withdrawn = insertUser("withdrawn", "탈퇴자", "WITHDRAWN", "PUBLIC");

        mockMvc.perform(path(publicIdOf(withdrawn)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("T10 최근 필름은 최신순이고 프레임 수는 전체 개수다")
    void recentFramesAreOrderedAndCountIsTotal() throws Exception {
        long olderMovie = insertMovie("먼저 본 영화", null, firstFlavorId);
        long newerMovie = insertMovie("나중에 본 영화", null, firstFlavorId);
        long olderRating = insertRating(ownerId, olderMovie, "3.0", null, null, BASE_TIME);
        Instant later = BASE_TIME.plusSeconds(60);
        long newerRating = insertRating(ownerId, newerMovie, "5.0", null, null, later);
        insertFrame(olderRating, ownerId, olderMovie, BASE_TIME);
        long newerFrame = insertFrame(newerRating, ownerId, newerMovie, later);

        mockMvc.perform(path(ownerPublicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.frameCount").value(2))
                .andExpect(jsonPath("$.recentFrames.length()").value(2))
                .andExpect(jsonPath("$.recentFrames[0].frameId").value(newerFrame))
                .andExpect(jsonPath("$.recentFrames[0].movie.title").value("나중에 본 영화"));
    }

    @Test
    @DisplayName("T11 평가가 없어도 기준 맛 목록과 빈 집계를 그대로 준다")
    void emptyProfileKeepsFlavorBuckets() throws Exception {
        long flavorCount =
                jdbc.sql("SELECT COUNT(*) FROM popcorn_flavors").query(Long.class).single();

        mockMvc.perform(path(ownerPublicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keywords.length()").value(0))
                .andExpect(jsonPath("$.stats.frameCount").value(0))
                .andExpect(jsonPath("$.stats.popcornCount").value(0))
                .andExpect(jsonPath("$.recentFrames.length()").value(0))
                .andExpect(jsonPath("$.publicReviewCount").value(0))
                .andExpect(jsonPath("$.popcorn.flavors.length()").value((int) flavorCount))
                .andExpect(jsonPath("$.popcorn.flavors[0].popcornCount").value(0))
                .andExpect(jsonPath("$.popcorn.flavors[0].averageScore").doesNotExist());
    }

    @Test
    @DisplayName("T12 유효하지 않은 토큰은 비회원과 같게 공개 프로필을 준다")
    void invalidTokenIsTreatedAsAnonymous() throws Exception {
        mockMvc.perform(path(ownerPublicId).header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.userId").value(ownerId));
    }

    /**
     * 차단 판정의 음성 대조군이다(리뷰 A-1).
     *
     * <p>200을 기대하는 나머지 테스트는 모두 비회원이거나 본인이라 차단 검사를 지나가지 않는다. 이 테스트가 없으면 {@code
     * UserBlockFilter.isBlocked}가 늘 참을 돌려주거나 조건이 뒤집혀도 전부 통과한다.
     */
    @Test
    @DisplayName("T14 차단 관계가 없는 로그인 사용자는 타인의 PUBLIC 프로필을 200으로 받는다")
    void unblockedMemberReadsPublicProfile() throws Exception {
        long viewer = insertUser("viewer", "조회자", "ACTIVE", "PUBLIC");

        mockMvc.perform(path(ownerPublicId).header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.userId").value(ownerId))
                .andExpect(jsonPath("$.isBlockedByMe").value(false));
    }

    /**
     * 프레임 수는 전체 개수이고 최근 필름은 상한까지만 온다(D-5·D-6, 리뷰 B-4).
     *
     * <p>상한보다 많은 프레임이 없으면 {@code totalCount}를 {@code items().size()}로 바꿔도 테스트가 통과한다.
     */
    @Test
    @DisplayName("T15 프레임이 상한을 넘으면 최근 필름은 20건이고 프레임 수는 전체다")
    void frameCountIsTotalWhileRecentFramesAreCapped() throws Exception {
        int total = 21;
        for (int index = 0; index < total; index++) {
            long movieId = insertMovie("영화 " + index, null, firstFlavorId);
            Instant createdAt = BASE_TIME.plusSeconds(index);
            long ratingId = insertRating(ownerId, movieId, "3.0", null, null, createdAt);
            insertFrame(ratingId, ownerId, movieId, createdAt);
        }

        mockMvc.perform(path(ownerPublicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.frameCount").value(total))
                .andExpect(jsonPath("$.recentFrames.length()").value(20));
    }

    /** {@code uuid} 컬럼이 대소문자를 가리지 않으므로 대문자 publicId도 같은 리소스다(리뷰 B-5). */
    @Test
    @DisplayName("T16 대문자 publicId도 같은 프로필을 준다")
    void uppercasePublicIdResolvesToSameProfile() throws Exception {
        mockMvc.perform(path(ownerPublicId.toUpperCase(java.util.Locale.ROOT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.userId").value(ownerId));
    }

    private static MockHttpServletRequestBuilder path(String publicId) {
        return get("/api/v1/users/{publicId}/profile", publicId);
    }

    private String bearer(long userId) {
        return "Bearer " + tokenProvider.createAccessToken(userId);
    }

    private long insertUser(String key, String nickname, String status, String visibility) {
        sequence++;
        return jdbc.sql(
                        """
                        INSERT INTO users(email,nickname,birth_date,gender,status,visibility)
                        VALUES (:email,:nickname,DATE '1994-04-04','OTHER',:status,:visibility)
                        RETURNING id
                        """)
                .param("email", key + "-" + sequence + "@feelm.test")
                .param("nickname", nickname + sequence)
                .param("status", status)
                .param("visibility", visibility)
                .query(Long.class)
                .single();
    }

    private String nicknameOf(long userId) {
        return jdbc.sql("SELECT nickname FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
    }

    private String publicIdOf(long userId) {
        return jdbc.sql("SELECT public_id FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
    }

    private long insertMovie(String title, String posterUrl, Long flavorId) {
        sequence++;
        return jdbc.sql(
                        """
                        INSERT INTO movies(tmdb_id,title,poster_url,representative_flavor_id)
                        VALUES (:tmdb,:title,:poster,:flavor)
                        RETURNING id
                        """)
                .param("tmdb", 1_620_000 + sequence)
                .param("title", title)
                .param("poster", posterUrl)
                .param("flavor", flavorId)
                .query(Long.class)
                .single();
    }

    private long insertRating(
            long userId,
            long movieId,
            String score,
            String comment,
            String visibility,
            Instant createdAt) {
        return jdbc.sql(
                        """
                        INSERT INTO ratings(
                            user_id,movie_id,score,comment,visibility,created_at,updated_at)
                        VALUES (
                            :user,:movie,CAST(:score AS numeric),:comment,:visibility,
                            :created,:created)
                        RETURNING id
                        """)
                .param("user", userId)
                .param("movie", movieId)
                .param("score", score)
                .param("comment", comment)
                .param("visibility", visibility)
                .param("created", Timestamp.from(createdAt))
                .query(Long.class)
                .single();
    }

    private long insertFrame(long ratingId, long userId, long movieId, Instant createdAt) {
        return jdbc.sql(
                        """
                        INSERT INTO frames(rating_id,user_id,movie_id,created_at)
                        VALUES (:rating,:user,:movie,:created)
                        RETURNING id
                        """)
                .param("rating", ratingId)
                .param("user", userId)
                .param("movie", movieId)
                .param("created", Timestamp.from(createdAt))
                .query(Long.class)
                .single();
    }

    private void insertBlock(long blockerId, long blockedId) {
        jdbc.sql("INSERT INTO user_blocks(blocker_id,blocked_id) VALUES (:blocker,:blocked)")
                .param("blocker", blockerId)
                .param("blocked", blockedId)
                .update();
    }

    private void insertTasteAnalysis(long userId, String code, String label) {
        String keywords = "[{\"code\":\"%s\",\"label\":\"%s\"}]".formatted(code, label);
        jdbc.sql(
                        """
                        INSERT INTO taste_analyses(
                            user_id,analysis,keywords,rating_count,analyzed_at)
                        VALUES (
                            :user,CAST(:analysis AS jsonb),CAST(:keywords AS jsonb),1,:analyzed)
                        """)
                .param("user", userId)
                .param("analysis", "{\"genres\":[],\"countries\":[],\"eras\":[],\"directors\":[]}")
                .param("keywords", keywords)
                .param("analyzed", Timestamp.from(BASE_TIME))
                .update();
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE users, movies RESTART IDENTITY CASCADE").update();
    }
}
