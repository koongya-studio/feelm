package com.feelm.backend.domain.social;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * API-087 공개 프로필 조회 계약 테스트.
 *
 * <p>실제 스키마를 Flyway로 올린 PostgreSQL에서 확인한다. {@code users.public_id}의 기본값, 팝콘 맛 기준정보 시드, {@code
 * user_blocks}의 양방향 판정이 모두 마이그레이션에 있어 손으로 만든 스키마로는 같은 것을 확인할 수 없다.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@SharedTestRuntime
@DisplayName("공개 프로필 API 계약 — API-087")
class ProfileReviewContractTest extends SharedPostgresContractTest {

    private static final Instant BASE_TIME = Instant.parse("2026-09-20T01:00:00Z");
    private static final long TEST_FLAVOR_ID = -749L;
    private static final String TEST_FLAVOR_CODE = "PUBLIC_PROFILE_TEST";

    @Autowired private MockMvc mockMvc;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private JdbcClient jdbc;

    @Autowired private tools.jackson.databind.ObjectMapper mapper;

    @Autowired private com.feelm.backend.global.security.JwtProperties jwtProperties;
    private int sequence;
    private long ownerId;
    private String ownerPublicId;
    private String ownerNickname;
    private long firstFlavorId;
    private String firstFlavorCode;

    @BeforeEach
    void setUp() {
        truncate();
        insertTestFlavor();
        sequence = 0;
        ownerId = insertUser("owner", "무비버디", "ACTIVE", "PUBLIC");
        ownerPublicId = publicIdOf(ownerId);
        ownerNickname = nicknameOf(ownerId);
        firstFlavorId = TEST_FLAVOR_ID;
        firstFlavorCode = TEST_FLAVOR_CODE;
    }

    @AfterEach
    void cleanUp() {
        truncate();
    }

    @Test
    void publicOnly() throws Exception {
        long movie = insertMovie("공개 영화", null, firstFlavorId);
        long rating = insertRating(ownerId, movie, "4.5", "공개 원문", "PUBLIC", BASE_TIME);
        jdbc.sql("UPDATE ratings SET is_spoiler=true WHERE id=:id").param("id", rating).update();
        insertRating(
                ownerId, insertMovie("비공개", null, firstFlavorId), "3", "비밀", "PRIVATE", BASE_TIME);
        insertRating(
                ownerId,
                insertMovie("공백", null, firstFlavorId),
                "3",
                "\u3000\t\n",
                "PUBLIC",
                BASE_TIME);
        insertRating(
                ownerId, insertMovie("null", null, firstFlavorId), "3", null, "PUBLIC", BASE_TIME);
        mockMvc.perform(path(ownerPublicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].movie.movieId").value(movie))
                .andExpect(jsonPath("$.items[0].movie.title").value("공개 영화"))
                .andExpect(jsonPath("$.items[0].score").value(4.5))
                .andExpect(jsonPath("$.items[0].comment").value("공개 원문"))
                .andExpect(jsonPath("$.items[0].spoiler").value(true))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void cursorPages() throws Exception {
        for (int i = 0; i < 11; i++)
            insertRating(
                    ownerId,
                    insertMovie("영화" + i, null, firstFlavorId),
                    "4",
                    "평" + i,
                    "PUBLIC",
                    BASE_TIME);
        var first =
                mapper.readTree(
                        mockMvc.perform(path(ownerPublicId))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertThat(first.path("items").size()).isEqualTo(10);
        String cursor = first.path("nextCursor").asText();
        var last =
                mapper.readTree(
                        mockMvc.perform(path(ownerPublicId).param("cursor", cursor))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertThat(last.path("items").size()).isEqualTo(1);
        assertThat(last.path("items").get(0).path("ratingId").asLong())
                .isLessThan(first.path("items").get(9).path("ratingId").asLong());
        assertThat(last.path("hasNext").asBoolean()).isFalse();
        assertThat(last.path("nextCursor").isNull()).isTrue();
        mockMvc.perform(path(ownerPublicId).param("size", "3"))
                .andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    void accessDenied() throws Exception {
        long viewer = insertUser("viewer", "조회자", "ACTIVE", "PUBLIC");
        mockMvc.perform(path("not-a-uuid")).andExpect(status().isNotFound());
        mockMvc.perform(path(java.util.UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        jdbc.sql("UPDATE users SET visibility='PRIVATE' WHERE id=:id")
                .param("id", ownerId)
                .update();
        mockMvc.perform(path(ownerPublicId)).andExpect(status().isNotFound());
        mockMvc.perform(
                        path(ownerPublicId)
                                .header(
                                        "Authorization",
                                        "Bearer " + tokenProvider.createAccessToken(ownerId)))
                .andExpect(status().isOk());
        jdbc.sql("UPDATE users SET visibility='PUBLIC' WHERE id=:id").param("id", ownerId).update();
        insertBlock(viewer, ownerId);
        mockMvc.perform(
                        path(ownerPublicId)
                                .header(
                                        "Authorization",
                                        "Bearer " + tokenProvider.createAccessToken(viewer)))
                .andExpect(status().isNotFound());
        jdbc.sql("DELETE FROM user_blocks").update();
        insertBlock(ownerId, viewer);
        mockMvc.perform(
                        path(ownerPublicId)
                                .header(
                                        "Authorization",
                                        "Bearer " + tokenProvider.createAccessToken(viewer)))
                .andExpect(status().isNotFound());
        jdbc.sql("UPDATE users SET status='WITHDRAWN' WHERE id=:id").param("id", ownerId).update();
        mockMvc.perform(path(ownerPublicId)).andExpect(status().isNotFound());
    }

    @Test
    void itemVisibility() throws Exception {
        insertRating(
                ownerId, insertMovie("공개", null, firstFlavorId), "4", "공개", "PUBLIC", BASE_TIME);
        insertRating(
                ownerId, insertMovie("비공개", null, firstFlavorId), "4", "비밀", "PRIVATE", BASE_TIME);
        jdbc.sql("UPDATE users SET review_visibility='PRIVATE' WHERE id=:id")
                .param("id", ownerId)
                .update();
        mockMvc.perform(path(ownerPublicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(
                        path(ownerPublicId)
                                .header(
                                        "Authorization",
                                        "Bearer " + tokenProvider.createAccessToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "51", "-1", "", "1.0", "1e1", " 3"})
    void invalidInput(String size) throws Exception {
        mockMvc.perform(path(ownerPublicId).param("size", size))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void invalidCursor() throws Exception {
        for (String cursor : new String[] {"", "tampered", " "})
            mockMvc.perform(path(ownerPublicId).param("cursor", cursor))
                    .andExpect(status().isBadRequest());
        insertRating(
                ownerId, insertMovie("하나", null, firstFlavorId), "4", "평", "PUBLIC", BASE_TIME);
        insertRating(ownerId, insertMovie("둘", null, firstFlavorId), "4", "평", "PUBLIC", BASE_TIME);
        var first =
                mapper.readTree(
                        mockMvc.perform(path(ownerPublicId).param("size", "1"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        String cursor = first.path("nextCursor").asText();
        long other = insertUser("other", "다른사람", "ACTIVE", "PUBLIC");
        mockMvc.perform(path(publicIdOf(other)).param("cursor", cursor))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        path(ownerPublicId)
                                .param("cursor", cursor)
                                .header(
                                        "Authorization",
                                        "Bearer " + tokenProvider.createAccessToken(other)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void visibilityChanged() throws Exception {
        long viewer = insertUser("viewer", "조회", "ACTIVE", "PUBLIC");
        insertRating(
                ownerId, insertMovie("하나", null, firstFlavorId), "4", "평", "PUBLIC", BASE_TIME);
        insertRating(ownerId, insertMovie("둘", null, firstFlavorId), "4", "평", "PUBLIC", BASE_TIME);
        String token = "Bearer " + tokenProvider.createAccessToken(viewer);
        var first =
                mapper.readTree(
                        mockMvc.perform(
                                        path(ownerPublicId)
                                                .param("size", "1")
                                                .header("Authorization", token))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        String cursor = first.path("nextCursor").asText();
        jdbc.sql("UPDATE users SET review_visibility='PRIVATE' WHERE id=:id")
                .param("id", ownerId)
                .update();
        mockMvc.perform(path(ownerPublicId).param("cursor", cursor).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        jdbc.sql("UPDATE users SET review_visibility='PUBLIC' WHERE id=:id")
                .param("id", ownerId)
                .update();
        jdbc.sql("DELETE FROM ratings WHERE user_id=:id").param("id", ownerId).update();
        mockMvc.perform(path(ownerPublicId).param("cursor", cursor).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        insertBlock(ownerId, viewer);
        mockMvc.perform(path(ownerPublicId).param("cursor", cursor).header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void mixedTimesAndThreeItemPages() throws Exception {
        java.util.List<Long> expected = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++)
            expected.add(
                    insertRating(
                            ownerId,
                            insertMovie("시각" + i, null, firstFlavorId),
                            "4",
                            "평",
                            "PUBLIC",
                            BASE_TIME.plusSeconds(i / 2)));
        java.util.Collections.reverse(expected);
        java.util.List<Long> actual = new java.util.ArrayList<>();
        String cursor = null;
        do {
            var request = path(ownerPublicId).param("size", "3");
            if (cursor != null) request.param("cursor", cursor);
            var page =
                    mapper.readTree(
                            mockMvc.perform(request)
                                    .andExpect(status().isOk())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            for (var item : page.path("items")) actual.add(item.path("ratingId").asLong());
            cursor = page.path("hasNext").asBoolean() ? page.path("nextCursor").asText() : null;
        } while (cursor != null);
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void profilePrivateBetweenPages() throws Exception {
        insertRating(
                ownerId, insertMovie("하나", null, firstFlavorId), "4", "평", "PUBLIC", BASE_TIME);
        insertRating(ownerId, insertMovie("둘", null, firstFlavorId), "4", "평", "PUBLIC", BASE_TIME);
        var first =
                mapper.readTree(
                        mockMvc.perform(path(ownerPublicId).param("size", "1"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        jdbc.sql("UPDATE users SET visibility='PRIVATE' WHERE id=:id")
                .param("id", ownerId)
                .update();
        mockMvc.perform(path(ownerPublicId).param("cursor", first.path("nextCursor").asText()))
                .andExpect(status().isNotFound());
    }

    @Test
    void openApiUsesDistinctResponseSchemas() throws Exception {
        var api =
                mapper.readTree(
                        mockMvc.perform(get("/v3/api-docs"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        var schemas = api.path("components").path("schemas");
        assertThat(
                        schemas.path("ProfileReviewPageResponse")
                                .path("properties")
                                .path("items")
                                .path("items")
                                .path("$ref")
                                .asText())
                .endsWith("/ProfileReviewItem");
        var properties = schemas.path("ProfileReviewItem").path("properties");
        for (String key :
                new String[] {"ratingId", "movie", "score", "comment", "spoiler", "createdAt"})
            assertThat(properties.has(key)).isTrue();
    }

    @Test
    void expiredTokenIsNotAnonymous() throws Exception {
        var properties =
                new com.feelm.backend.global.security.JwtProperties(
                        jwtProperties.secret(),
                        java.time.Duration.ofSeconds(-1),
                        jwtProperties.refreshTokenTtl(),
                        jwtProperties.signupTokenTtl(),
                        jwtProperties.emailVerificationTokenTtl(),
                        jwtProperties.refreshRotationGrace(),
                        jwtProperties.refreshCookie());
        var expired =
                new com.feelm.backend.global.security.JjwtTokenProvider(properties)
                        .createAccessToken(ownerId);
        mockMvc.perform(path(ownerPublicId).header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void optionalAuth() throws Exception {
        mockMvc.perform(path(ownerPublicId)).andExpect(status().isOk());
        long viewer = insertUser("authenticated", "인증조회", "ACTIVE", "PUBLIC");
        mockMvc.perform(
                        path(ownerPublicId)
                                .header(
                                        "Authorization",
                                        "Bearer " + tokenProvider.createAccessToken(viewer)))
                .andExpect(status().isOk());
        jdbc.sql("UPDATE users SET status='WITHDRAWN' WHERE id=:id").param("id", viewer).update();
        mockMvc.perform(
                        path(ownerPublicId)
                                .header(
                                        "Authorization",
                                        "Bearer " + tokenProvider.createAccessToken(viewer)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(path(ownerPublicId).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me/reviews")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/ME/reviews")).andExpect(status().isUnauthorized());
    }

    private static MockHttpServletRequestBuilder path(String publicId) {
        return get("/api/v1/users/{publicId}/reviews", publicId);
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

    private void insertTestFlavor() {
        jdbc.sql(
                        """
                        INSERT INTO popcorn_flavors(
                            id,code,display_name,color,description,sort_order,cluster_id,
                            representative_keywords)
                        VALUES (
                            :id,:code,'공개 프로필 테스트 맛','#000000','테스트 맛 설명',-749,-749,
                            CAST('[]' AS jsonb))
                        """)
                .param("id", TEST_FLAVOR_ID)
                .param("code", TEST_FLAVOR_CODE)
                .update();
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE users, movies RESTART IDENTITY CASCADE").update();
        jdbc.sql(
                        """
                        DELETE FROM popcorn_flavors
                        WHERE id = :id OR code = :code OR cluster_id = -749
                        """)
                .param("id", TEST_FLAVOR_ID)
                .param("code", TEST_FLAVOR_CODE)
                .update();
    }
}
