package com.feelm.backend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.user.repository.UserBlockRepository;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** API-020~022: 실제 마이그레이션과 PostgreSQL을 사용하는 사용자 차단 HTTP 계약 테스트. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@DisplayName("API-020~022 사용자 차단·해제·목록")
class UserBlockApiContractTest extends SharedPostgresContractTest {

    private static final Instant BLOCKED_AT = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired ObjectMapper objectMapper;

    /** T25에서 선조회만 통과시키고 UNIQUE 제약 경로를 결정적으로 태우기 위한 spy. 기본 동작은 실제 빈과 같다. */
    @MockitoSpyBean UserBlockRepository blockRepositorySpy;

    private ExecutorService executor;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        user(10, "blocker", "ACTIVE");
        user(20, "target20", "ACTIVE");
        user(30, "target30", "ACTIVE");
        user(40, "target40", "ACTIVE");
        user(50, "left50", "WITHDRAWN");
        user(60, "withdrawn60", "WITHDRAWN");
    }

    @AfterEach
    void resetSpy() {
        Mockito.reset(blockRepositorySpy);
    }

    @AfterEach
    void stopExecutor() throws InterruptedException {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    // ----- API-020 사용자 차단 -----

    @Test
    @DisplayName("T1 인증 없이 차단할 수 없다")
    void blockRequiresAuthentication() throws Exception {
        mvc.perform(post(blockPath(20)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post(blockPath(20)).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(blockCount()).isZero();
    }

    @Test
    @DisplayName("T2 활성 사용자를 차단하면 201과 차단 시각을 반환하고 행이 하나 생긴다")
    void blocksUser() throws Exception {
        String body =
                block(10, 20)
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.blockedUserId").value(20))
                        .andExpect(jsonPath("$.blockedAt").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        assertThat(Instant.parse(response.get("blockedAt").asText())).isNotNull();
        assertThat(blockCount(10, 20)).isEqualTo(1);
    }

    @Test
    @DisplayName("T3 자기 자신은 차단할 수 없다")
    void rejectsSelfBlock() throws Exception {
        block(10, 10)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_BLOCK_SELF"));

        assertThat(blockCount()).isZero();
    }

    @Test
    @DisplayName("T4 이미 차단한 사용자를 다시 차단하면 409다")
    void rejectsDuplicateBlock() throws Exception {
        block(10, 20).andExpect(status().isCreated());

        block(10, 20)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_BLOCKED"));

        assertThat(blockCount(10, 20)).isEqualTo(1);
    }

    @Test
    @DisplayName("T5 존재하지 않는 사용자는 404다")
    void rejectsUnknownTarget() throws Exception {
        block(10, 999)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(blockCount()).isZero();
    }

    @Test
    @DisplayName("T6 활성 계정이 아닌 사용자는 존재를 노출하지 않고 404다")
    void rejectsNonActiveTarget() throws Exception {
        for (long target : new long[] {50, 60}) {
            block(10, target)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }

        assertThat(blockCount()).isZero();
    }

    @Test
    @DisplayName("T7 상대가 먼저 나를 차단했어도 반대 방향 차단은 별도 행으로 저장된다")
    void allowsBlockInOppositeDirection() throws Exception {
        storedBlock(20, 10, BLOCKED_AT);

        block(10, 20).andExpect(status().isCreated());

        assertThat(blockCount(10, 20)).isEqualTo(1);
        assertThat(blockCount(20, 10)).isEqualTo(1);
    }

    @Test
    @DisplayName("T8 같은 차단이 동시에 들어와도 행은 하나만 남는다")
    void concurrentDuplicateBlockKeepsOneRow() throws Exception {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        String token = bearer(10);
        Future<Integer> first = executor.submit(() -> concurrentBlock(20, token, ready, start));
        Future<Integer> second = executor.submit(() -> concurrentBlock(20, token, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<Integer> statuses =
                List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(blockCount(10, 20)).isEqualTo(1);
    }

    @Test
    @DisplayName("T25 선조회를 통과해도 UNIQUE 제약이 중복 차단을 최종 판정한다")
    void uniqueConstraintDecidesDuplicateBlock() throws Exception {
        // T8은 두 요청이 직렬화되면 선조회에서 409가 나도 통과한다. 여기서는 선조회만 통과시켜
        // 제약 위반 → ALREADY_BLOCKED 변환 경로를 결정적으로 실행한다(D4의 안전망).
        storedBlock(10, 20, BLOCKED_AT);
        doReturn(false).when(blockRepositorySpy).existsByBlockerIdAndBlockedId(10L, 20L);

        block(10, 20)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_BLOCKED"));

        assertThat(blockCount(10, 20)).isEqualTo(1);
    }

    // ----- API-021 사용자 차단 해제 -----

    @Test
    @DisplayName("T9 인증 없이 차단을 해제할 수 없다")
    void unblockRequiresAuthentication() throws Exception {
        storedBlock(10, 20, BLOCKED_AT);

        mvc.perform(delete(blockPath(20)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(delete(blockPath(20)).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(blockCount(10, 20)).isEqualTo(1);
    }

    @Test
    @DisplayName("T10 차단을 해제하면 204이고 행이 사라진다")
    void unblocksUser() throws Exception {
        storedBlock(10, 20, BLOCKED_AT);

        unblock(10, 20).andExpect(status().isNoContent());

        assertThat(blockCount(10, 20)).isZero();
    }

    @Test
    @DisplayName("T11 차단 관계가 없어도 해제는 204다 — 완전 멱등")
    void unblockWithoutRelationIsIdempotent() throws Exception {
        for (long target : new long[] {20, 999, 10}) {
            unblock(10, target).andExpect(status().isNoContent());
        }

        assertThat(blockCount()).isZero();

        storedBlock(10, 20, BLOCKED_AT);
        unblock(10, 20).andExpect(status().isNoContent());
        unblock(10, 20).andExpect(status().isNoContent());

        assertThat(blockCount()).isZero();
    }

    @Test
    @DisplayName("T12 해제는 역방향 차단 행을 지우지 않는다")
    void unblockKeepsReverseBlock() throws Exception {
        storedBlock(10, 20, BLOCKED_AT);
        storedBlock(20, 10, BLOCKED_AT);

        unblock(10, 20).andExpect(status().isNoContent());

        assertThat(blockCount(10, 20)).isZero();
        assertThat(blockCount(20, 10)).isEqualTo(1);
    }

    // ----- API-022 차단 목록 조회 -----

    @Test
    @DisplayName("T13 인증 없이 차단 목록을 볼 수 없다")
    void listRequiresAuthentication() throws Exception {
        mvc.perform(get(BLOCKS_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get(BLOCKS_PATH).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("T14 차단 목록은 최신순이며 닉네임과 차단 시각을 함께 준다")
    void listsBlockedUsersNewestFirst() throws Exception {
        storedBlock(10, 20, BLOCKED_AT);
        storedBlock(10, 30, BLOCKED_AT.plusSeconds(60));

        blocks(10, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].userId").value(30))
                .andExpect(jsonPath("$.items[0].nickname").value("target30"))
                .andExpect(jsonPath("$.items[0].blockedAt").value("2026-09-01T00:01:00Z"))
                .andExpect(jsonPath("$.items[1].userId").value(20))
                .andExpect(jsonPath("$.items[1].nickname").value("target20"))
                .andExpect(jsonPath("$.items[1].blockedAt").value("2026-09-01T00:00:00Z"))
                .andExpect(jsonPath("$.nextCursor").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("T15 커서로 이어 읽으면 중복·누락 없이 전부 읽는다 — 같은 시각은 id 역순")
    void pagesBlockedUsersWithCursor() throws Exception {
        storedBlock(10, 20, BLOCKED_AT.plusSeconds(60));
        storedBlock(10, 30, BLOCKED_AT);
        storedBlock(10, 40, BLOCKED_AT);

        String body =
                blocks(10, null, 2)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items", hasSize(2)))
                        .andExpect(jsonPath("$.items[0].userId").value(20))
                        .andExpect(jsonPath("$.items[1].userId").value(40))
                        .andExpect(jsonPath("$.hasNext").value(true))
                        .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String cursor = objectMapper.readTree(body).get("nextCursor").asText();

        blocks(10, cursor, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].userId").value(30))
                .andExpect(jsonPath("$.nextCursor").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("T16 잘못된 size와 커서는 400이다")
    void rejectsInvalidPagingParameters() throws Exception {
        storedBlock(10, 20, BLOCKED_AT);

        for (Integer size : new Integer[] {0, -1, 51}) {
            blocks(10, null, size)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        String[] cursors = {
            "not-base64!!",
            "Zm9vOmJhcg",
            "MjA=",
            // 형식은 맞지만 timestamptz로 저장할 수 없는 시각. 검증에서 걸러지지 않으면 DB가 거절해 500이 된다.
            forgedCursor("+0500000-01-01T00:00:00Z:1"),
            forgedCursor("+1000000000-12-31T23:59:59Z:1"),
            forgedCursor("1900-01-01T00:00:00Z:1")
        };
        for (String cursor : cursors) {
            blocks(10, cursor, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    @DisplayName("T17 나를 차단한 사용자는 내 차단 목록에 나오지 않는다")
    void listExcludesUsersWhoBlockedMe() throws Exception {
        storedBlock(20, 10, BLOCKED_AT);

        blocks(10, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("T26 size가 숫자가 아니면 400이다")
    void rejectsNonNumericSize() throws Exception {
        mvc.perform(get(BLOCKS_PATH).header("Authorization", bearer(10)).param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("T27 양방향으로 차단한 상대는 목록에 한 번만 나온다")
    void listsMutualBlockOnce() throws Exception {
        storedBlock(10, 20, BLOCKED_AT);
        storedBlock(20, 10, BLOCKED_AT);

        blocks(10, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].userId").value(20))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("T28 행 수가 size와 같으면 다음 페이지가 없다")
    void reportsNoNextPageWhenRowsEqualSize() throws Exception {
        storedBlock(10, 20, BLOCKED_AT);
        storedBlock(10, 30, BLOCKED_AT.plusSeconds(60));

        blocks(10, null, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.nextCursor").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    // ----- 공통: 요청자 계정 상태 -----

    @Test
    @DisplayName("T24 활성 계정이 아닌 요청자는 세 경로 모두 401이다")
    void rejectsNonActiveRequester() throws Exception {
        storedBlock(50, 20, BLOCKED_AT);
        storedBlock(60, 20, BLOCKED_AT);

        for (long requester : new long[] {50, 60}) {
            block(requester, 30)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
            unblock(requester, 20)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
            mvc.perform(get(BLOCKS_PATH).header("Authorization", bearer(requester)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }

        assertThat(blockCount(50, 20)).isEqualTo(1);
        assertThat(blockCount(60, 20)).isEqualTo(1);
        assertThat(blockCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("T29 사라진 계정의 유효한 토큰도 세 경로 모두 401이다")
    void rejectsTokenOfMissingAccount() throws Exception {
        long removed = 999;

        block(removed, 20)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        unblock(removed, 20)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get(BLOCKS_PATH).header("Authorization", bearer(removed)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(blockCount()).isZero();
    }

    // ----- helpers -----

    private static final String BLOCKS_PATH = "/api/v1/users/me/blocks";

    private ResultActions block(long requester, long target) throws Exception {
        return mvc.perform(post(blockPath(target)).header("Authorization", bearer(requester)));
    }

    private ResultActions unblock(long requester, long target) throws Exception {
        return mvc.perform(delete(blockPath(target)).header("Authorization", bearer(requester)));
    }

    private ResultActions blocks(long requester, String cursor, Integer size) throws Exception {
        var request = get(BLOCKS_PATH).header("Authorization", bearer(requester));
        if (cursor != null) {
            request = request.param("cursor", cursor);
        }
        if (size != null) {
            request = request.param("size", String.valueOf(size));
        }
        return mvc.perform(request);
    }

    private int concurrentBlock(
            long target, String token, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return mvc.perform(post(blockPath(target)).header("Authorization", token))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    /** 서버가 만들지 않은 커서를 흉내낸다. 인코딩 형식만 맞추고 값은 임의로 넣는다. */
    private String forgedCursor(String raw) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String blockPath(long target) {
        return "/api/v1/users/" + target + "/block";
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    private void user(long id, String nickname, String status) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER',?)",
                id,
                nickname + "@example.test",
                nickname,
                status);
    }

    private void storedBlock(long blocker, long blocked, Instant createdAt) {
        jdbc.update(
                "INSERT INTO user_blocks(blocker_id,blocked_id,created_at) VALUES (?,?,?)",
                blocker,
                blocked,
                Timestamp.from(createdAt));
    }

    private long blockCount() {
        return jdbc.queryForObject("SELECT count(*) FROM user_blocks", Long.class);
    }

    private long blockCount(long blocker, long blocked) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM user_blocks WHERE blocker_id=? AND blocked_id=?",
                Long.class,
                blocker,
                blocked);
    }
}
