package com.feelm.backend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** API-019: 실제 마이그레이션과 PostgreSQL을 사용하는 사용자 검색 HTTP 계약 테스트. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("API-019 사용자 검색")
class UserSearchApiContractTest {

    private static final String SEARCH_PATH = "/api/v1/users/search";
    private static final long REQUESTER = 10;

    /**
     * 정렬이 이 API의 핵심 계약이라 운영과 같은 {@code C.UTF-8} 로케일로 띄운다. 기본 로케일로 두면 정규화 닉네임의 비교 순서가 운영과 달라져, 정렬과
     * 커서 경계를 다른 콜레이션에서 검증하게 된다(마이그레이션 테스트 6종과 같은 설정).
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withEnv("POSTGRES_INITDB_ARGS", "--locale=C.UTF-8");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        user(REQUESTER, "searcher", "ACTIVE", "PRIVATE");
    }

    @Test
    @DisplayName("T1 인증 없이 검색할 수 없다")
    void requiresAuthentication() throws Exception {
        mvc.perform(get(SEARCH_PATH).param("nickname", "movie"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(
                        get(SEARCH_PATH)
                                .param("nickname", "movie")
                                .header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("T2 닉네임 접두가 일치하는 회원을 반환한다")
    void findsByNicknamePrefix() throws Exception {
        user(20, "movielover", "ACTIVE", "PUBLIC");
        user(30, "movienight", "ACTIVE", "PRIVATE");
        user(40, "bookworm", "ACTIVE", "PUBLIC");

        search("movie", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].userId").value(20))
                .andExpect(jsonPath("$.items[0].nickname").value("movielover"))
                .andExpect(jsonPath("$.items[0].visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.items[1].userId").value(30))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("T3 대소문자와 공백을 정규화해 일치시킨다")
    void normalizesCaseAndSpaces() throws Exception {
        user(20, "MovieLover", "ACTIVE", "PUBLIC");

        search(" mov ie ", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].nickname").value("MovieLover"));
    }

    @Test
    @DisplayName("T4 PRIVATE 사용자도 결과에 포함되고 visibility가 그대로 내려온다")
    void includesPrivateUsers() throws Exception {
        user(20, "prilover", "ACTIVE", "PRIVATE");
        user(30, "prinight", "ACTIVE", "PUBLIC");

        search("pri", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].userId").value(20))
                .andExpect(jsonPath("$.items[0].visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.items[1].visibility").value("PUBLIC"));
    }

    @Test
    @DisplayName("T5 본인은 결과에서 제외한다")
    void excludesRequester() throws Exception {
        user(20, "searchmate", "ACTIVE", "PUBLIC");

        search("search", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].userId").value(20));
    }

    @Test
    @DisplayName("T6 내가 차단한 사용자는 결과에서 제외한다")
    void excludesUsersIBlocked() throws Exception {
        user(20, "movielover", "ACTIVE", "PUBLIC");
        user(30, "movienight", "ACTIVE", "PUBLIC");
        storedBlock(REQUESTER, 20);

        search("movie", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].userId").value(30));
    }

    @Test
    @DisplayName("T7 나를 차단한 사용자도 결과에서 제외한다")
    void excludesUsersWhoBlockedMe() throws Exception {
        user(20, "movielover", "ACTIVE", "PUBLIC");
        user(30, "movienight", "ACTIVE", "PUBLIC");
        storedBlock(20, REQUESTER);

        search("movie", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].userId").value(30));
    }

    @Test
    @DisplayName("T8 탈퇴·미인증 계정은 결과에서 제외한다")
    void excludesInactiveAccounts() throws Exception {
        user(20, "moviepending", "PENDING_VERIFICATION", "PUBLIC");
        user(30, "moviegone", "WITHDRAWN", "PUBLIC");
        user(40, "movielover", "ACTIVE", "PUBLIC");

        search("movie", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].userId").value(40));
    }

    @Test
    @DisplayName("T9 nickname 파라미터가 없으면 400이다")
    void rejectsMissingNickname() throws Exception {
        mvc.perform(get(SEARCH_PATH).header("Authorization", bearer(REQUESTER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("T10 정규화 후 2자 미만이면 400이다")
    void rejectsTooShortNickname() throws Exception {
        for (String nickname : new String[] {"", " a ", "a", "  "}) {
            search(nickname, null, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    @DisplayName("T11 정규화 후 10자를 넘으면 400이다")
    void rejectsTooLongNickname() throws Exception {
        search("a".repeat(11), null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("T12 %는 와일드카드가 아니라 리터럴이다")
    void treatsPercentAsLiteral() throws Exception {
        user(20, "abcdef", "ACTIVE", "PUBLIC");

        search("ab%", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("T13 _는 한 글자 와일드카드가 아니라 리터럴이다")
    void treatsUnderscoreAsLiteral() throws Exception {
        user(20, "abcdef", "ACTIVE", "PUBLIC");
        user(30, "ab_cd", "ACTIVE", "PUBLIC");

        search("ab_", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].userId").value(30));
    }

    @Test
    @DisplayName("T28 닉네임에 담길 수 없는 제어문자가 섞인 검색어는 빈 결과다")
    void treatsControlCharactersAsNoMatch() throws Exception {
        user(20, "abcdef", "ACTIVE", "PUBLIC");

        for (String nickname : new String[] {"ab\0", "ab\7", "\0ab"}) {
            search(nickname, null, null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(0)))
                    .andExpect(jsonPath("$.hasNext").value(false));
        }
    }

    @Test
    @DisplayName("T14 정규화 닉네임 오름차순, 동률은 id 오름차순으로 정렬한다")
    void sortsByNormalizedNicknameThenId() throws Exception {
        user(20, "MovieC", "ACTIVE", "PUBLIC");
        user(30, "moviea", "ACTIVE", "PUBLIC");
        user(40, "MOVIEB", "ACTIVE", "PUBLIC");

        search("movie", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].nickname").value("moviea"))
                .andExpect(jsonPath("$.items[1].nickname").value("MOVIEB"))
                .andExpect(jsonPath("$.items[2].nickname").value("MovieC"));
    }

    @Test
    @DisplayName("T15 size를 생략하면 20건씩 반환한다")
    void usesDefaultPageSize() throws Exception {
        for (int index = 0; index < 21; index++) {
            user(100 + index, String.format("movie%02d", index), "ACTIVE", "PUBLIC");
        }

        search("movie", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    @DisplayName("T16 size가 1~50을 벗어나면 400이다")
    void rejectsOutOfRangeSize() throws Exception {
        for (int size : new int[] {0, -1, 51}) {
            search("movie", null, size)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    @DisplayName("T17 size가 숫자가 아니면 400이다")
    void rejectsNonNumericSize() throws Exception {
        mvc.perform(
                        get(SEARCH_PATH)
                                .header("Authorization", bearer(REQUESTER))
                                .param("nickname", "movie")
                                .param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("T18 커서로 끝까지 순회하면 중복·누락 없이 전부 읽는다")
    void pagesThroughWithCursor() throws Exception {
        for (int index = 0; index < 5; index++) {
            user(100 + index, "movie" + index, "ACTIVE", "PUBLIC");
        }

        List<Long> collected = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 3; page++) {
            JsonNode body = body(search("movie", cursor, 2));
            body.get("items").forEach(item -> collected.add(item.get("userId").asLong()));
            cursor = body.get("nextCursor").isNull() ? null : body.get("nextCursor").asText();
            assertThat(body.get("hasNext").asBoolean()).isEqualTo(page < 2);
        }

        assertThat(cursor).isNull();
        assertThat(collected).containsExactly(100L, 101L, 102L, 103L, 104L);
    }

    @Test
    @DisplayName("T19 마지막 페이지는 nextCursor가 없다")
    void lastPageHasNoCursor() throws Exception {
        user(20, "movielover", "ACTIVE", "PUBLIC");
        user(30, "movienight", "ACTIVE", "PUBLIC");

        search("movie", null, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.nextCursor").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("T20 형식이 깨지거나 위조된 커서는 400이다")
    void rejectsMalformedCursor() throws Exception {
        String[] cursors = {
            "not-base64!!",
            "",
            forgedCursor("movielover"),
            forgedCursor("movielover:0"),
            forgedCursor("movielover:-1"),
            forgedCursor("movielover:not-a-number"),
            forgedCursor("n".repeat(21) + ":1"),
            forgedCursor("m\0o:1"),
            forgedCursor("mo\7:1"),
            "A".repeat(129)
        };

        for (String cursor : cursors) {
            search("movie", cursor, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    @DisplayName("T21 일치하는 회원이 없으면 빈 페이지를 반환한다")
    void returnsEmptyPageWhenNoMatch() throws Exception {
        user(20, "bookworm", "ACTIVE", "PUBLIC");

        search("movie", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.nextCursor").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("T22 활성 계정이 아닌 요청자는 401이다")
    void rejectsInactiveRequester() throws Exception {
        user(50, "pending50", "PENDING_VERIFICATION", "PUBLIC");
        user(60, "withdrawn60", "WITHDRAWN", "PUBLIC");

        for (long requester : new long[] {50, 60}) {
            mvc.perform(
                            get(SEARCH_PATH)
                                    .header("Authorization", bearer(requester))
                                    .param("nickname", "movie"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    @DisplayName("T23 페이지네이션 도중 상대가 나를 차단해도 커서 위치가 흔들리지 않는다")
    void keepsPageStableWhenCounterpartBlockedMidPagination() throws Exception {
        for (int index = 0; index < 5; index++) {
            user(100 + index, "movie" + index, "ACTIVE", "PUBLIC");
        }

        JsonNode first = body(search("movie", null, 2));
        assertThat(userIds(first)).containsExactly(100L, 101L);

        // 2페이지를 읽기 전에 남은 대상 중 하나가 요청자를 차단한다.
        storedBlock(103, REQUESTER);

        JsonNode second = body(search("movie", first.get("nextCursor").asText(), 2));
        assertThat(userIds(second)).containsExactly(102L, 104L);
        assertThat(second.get("hasNext").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("T27 접두 검색 인덱스가 마이그레이션으로 생성된다")
    void createsPrefixSearchIndex() throws Exception {
        Long count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_indexes"
                                + " WHERE tablename = 'users' AND indexname = ?",
                        Long.class,
                        "ix_users_nickname_normalized_pattern");

        assertThat(count).isEqualTo(1L);
    }

    private ResultActions search(String nickname, String cursor, Integer size) throws Exception {
        var request = get(SEARCH_PATH).header("Authorization", bearer(REQUESTER));
        if (nickname != null) {
            request = request.param("nickname", nickname);
        }
        if (cursor != null) {
            request = request.param("cursor", cursor);
        }
        if (size != null) {
            request = request.param("size", String.valueOf(size));
        }
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return objectMapper.readTree(
                actions.andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<Long> userIds(JsonNode body) {
        List<Long> ids = new ArrayList<>();
        body.get("items").forEach(item -> ids.add(item.get("userId").asLong()));
        return ids;
    }

    /** 서버가 만들지 않은 커서를 흉내낸다. 인코딩 형식만 맞추고 값은 임의로 넣는다. */
    private static String forgedCursor(String raw) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    private void user(long id, String nickname, String status, String visibility) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status,visibility)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER',?,?)",
                id,
                nickname + "@example.test",
                nickname,
                status,
                visibility);
    }

    private void storedBlock(long blocker, long blocked) {
        jdbc.update(
                "INSERT INTO user_blocks(blocker_id,blocked_id) VALUES (?,?)", blocker, blocked);
    }
}
