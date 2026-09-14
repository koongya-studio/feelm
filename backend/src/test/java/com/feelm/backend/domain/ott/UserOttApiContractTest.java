package com.feelm.backend.domain.ott;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.ott.repository.OttRepository;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** API-026·027: 실제 마이그레이션과 PostgreSQL을 사용하는 내 구독 OTT HTTP 계약 테스트. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@DisplayName("API-026·027 내 구독 OTT 조회·설정")
class UserOttApiContractTest extends SharedPostgresContractTest {

    private static final String MY_OTTS_PATH = "/api/v1/users/me/otts";
    private static final long ACTIVE = 10;
    private static final long WITHDRAWN = 20;

    /** 교체가 자기 행만 건드리는지 보기 위한 제3자. 이 사용자의 구독은 어떤 테스트에서도 바뀌면 안 된다. */
    private static final long OTHER = 30;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;

    /** T13에서 입력 검증이 존재 확인 조회보다 앞선다는 것을 보기 위한 spy. 기본 동작은 실제 빈과 같다. */
    @MockitoSpyBean OttRepository ottRepositorySpy;

    private ExecutorService executor;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE otts RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        ott(1, "NETFLIX", "넷플릭스", 8);
        ott(2, "DISNEY_PLUS", "디즈니+", 337);
        ott(3, "WATCHA", "왓챠", 97);
        ott(4, "WAVVE", "웨이브", 356);
        ott(5, "TVING", "티빙", 1883);
        user(ACTIVE, "active", "ACTIVE");
        user(WITHDRAWN, "withdrawn", "WITHDRAWN");
        user(OTHER, "other", "ACTIVE");
        subscribed(OTHER, 5);
    }

    @AfterEach
    void resetSpy() {
        Mockito.reset(ottRepositorySpy);
    }

    /** 어떤 경로도 다른 사용자의 구독을 건드리지 않는다(리뷰 B-5). */
    @AfterEach
    void keepsOtherUserSubscription() {
        assertThat(subscribedIds(OTHER)).containsExactly(5L);
    }

    @AfterEach
    void stopExecutor() throws InterruptedException {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    // ----- API-026 내 구독 OTT 조회 -----

    @Test
    @DisplayName("T3 설정한 적이 없으면 빈 배열이다")
    void returnsEmptyWhenNeverSet() throws Exception {
        mvc.perform(get(MY_OTTS_PATH).header("Authorization", bearer(ACTIVE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ottIds").isArray())
                .andExpect(jsonPath("$.ottIds").isEmpty());
    }

    @Test
    @DisplayName("T4 구독 중인 식별자를 오름차순으로 내려준다")
    void returnsSubscribedIdsAscending() throws Exception {
        subscribed(ACTIVE, 3);
        subscribed(ACTIVE, 1);

        mvc.perform(get(MY_OTTS_PATH).header("Authorization", bearer(ACTIVE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ottIds[0]").value(1))
                .andExpect(jsonPath("$.ottIds[1]").value(3))
                .andExpect(jsonPath("$.ottIds.length()").value(2));
    }

    @Test
    @DisplayName("T5 인증 없이 조회할 수 없다")
    void rejectsUnauthenticatedRead() throws Exception {
        mvc.perform(get(MY_OTTS_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get(MY_OTTS_PATH).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("T6 활성 계정이 아니면 조회할 수 없다")
    void rejectsWithdrawnAccountRead() throws Exception {
        mvc.perform(get(MY_OTTS_PATH).header("Authorization", bearer(WITHDRAWN)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ----- API-027 내 구독 OTT 설정 -----

    @Test
    @DisplayName("T7 목록을 전체 교체한다")
    void replacesWholeSubscription() throws Exception {
        subscribed(ACTIVE, 1);
        subscribed(ACTIVE, 2);

        replace(ACTIVE, "[3,4]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ottIds[0]").value(3))
                .andExpect(jsonPath("$.ottIds[1]").value(4))
                .andExpect(jsonPath("$.ottIds.length()").value(2));

        assertThat(subscribedIds(ACTIVE)).containsExactly(3L, 4L);
    }

    @Test
    @DisplayName("T8 빈 배열이면 전체 해제한다")
    void clearsWithEmptyArray() throws Exception {
        subscribed(ACTIVE, 1);
        subscribed(ACTIVE, 2);

        replace(ACTIVE, "[]").andExpect(status().isOk()).andExpect(jsonPath("$.ottIds").isEmpty());

        assertThat(subscribedIds(ACTIVE)).isEmpty();
    }

    @Test
    @DisplayName("T9 같은 요청을 반복해도 결과가 같다")
    void isIdempotentOnRepeatedRequest() throws Exception {
        replace(ACTIVE, "[1,3]").andExpect(status().isOk());
        replace(ACTIVE, "[1,3]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ottIds.length()").value(2));

        assertThat(subscribedIds(ACTIVE)).containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("T10 존재하지 않는 OTT가 있으면 삭제 전에 거절한다")
    void rejectsUnknownOttIdBeforeDeleting() throws Exception {
        subscribed(ACTIVE, 1);

        replace(ACTIVE, "[1,9999]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(subscribedIds(ACTIVE)).containsExactly(1L);
    }

    @Test
    @DisplayName("T11 같은 식별자를 여러 번 보내도 한 행만 남는다")
    void deduplicatesRequestedIds() throws Exception {
        replace(ACTIVE, "[1,1,3]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ottIds[0]").value(1))
                .andExpect(jsonPath("$.ottIds[1]").value(3))
                .andExpect(jsonPath("$.ottIds.length()").value(2));

        assertThat(subscribedIds(ACTIVE)).containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("T12 ottIds가 없으면 거절한다")
    void rejectsMissingOttIds() throws Exception {
        subscribed(ACTIVE, 1);

        replace(ACTIVE, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(subscribedIds(ACTIVE)).containsExactly(1L);
    }

    @Test
    @DisplayName("T13 상한을 넘는 요청은 존재 확인 조회 전에 거절한다")
    void rejectsTooManyOttIds() throws Exception {
        subscribed(ACTIVE, 2);
        String tooMany =
                IntStream.rangeClosed(1, 51)
                        .mapToObj(String::valueOf)
                        .collect(Collectors.joining(",", "[", "]"));

        replace(ACTIVE, tooMany)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(ottRepositorySpy, never()).countByIdIn(any());
        assertThat(subscribedIds(ACTIVE)).containsExactly(2L);
    }

    @Test
    @DisplayName("T16 원소가 null이거나 양수가 아니면 거절한다")
    void rejectsNullOrNonPositiveOttId() throws Exception {
        subscribed(ACTIVE, 1);

        for (String body : new String[] {"[null]", "[0]", "[-1]", "[1,null]"}) {
            replace(ACTIVE, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        assertThat(subscribedIds(ACTIVE)).containsExactly(1L);
    }

    @Test
    @DisplayName("T17 존재 검증 뒤 OTT가 사라지면 400으로 응답하고 기존 구독을 되돌린다")
    void rollsBackWhenOttDisappearsAfterValidation() throws Exception {
        subscribed(ACTIVE, 1);
        subscribed(ACTIVE, 2);
        // 존재 검증만 통과시켜 삽입 시점의 외래키 위반 경로를 결정적으로 태운다(리뷰 B-1).
        doReturn(2L).when(ottRepositorySpy).countByIdIn(List.of(3L, 9999L));

        replace(ACTIVE, "[3,9999]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 삭제까지 실행된 뒤 실패했으므로, 기존 구독이 남아 있다는 것은 롤백이 실제로 동작했다는 뜻이다.
        assertThat(subscribedIds(ACTIVE)).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("T14 동시 교체 요청의 결과가 섞이지 않는다")
    void doesNotInterleaveConcurrentReplacements() throws Exception {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        String token = bearer(ACTIVE);
        Future<Integer> first =
                executor.submit(() -> concurrentReplace("[1,2]", token, ready, start));
        Future<Integer> second =
                executor.submit(() -> concurrentReplace("[3,4]", token, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        // 한쪽 요청이 통째로 이겨야 한다. 합집합 {1,2,3,4}나 부분집합이 남으면 교차 실행된 것이다.
        assertThat(subscribedIds(ACTIVE)).isIn(List.of(1L, 2L), List.of(3L, 4L));
    }

    @Test
    @DisplayName("T15 활성 계정이 아니면 설정할 수 없다")
    void rejectsWithdrawnAccountWrite() throws Exception {
        replace(WITHDRAWN, "[1]")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(subscribedIds(WITHDRAWN)).isEmpty();
    }

    private ResultActions replace(long userId, String ottIdsJson) throws Exception {
        return mvc.perform(
                put(MY_OTTS_PATH)
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ottIdsJson)));
    }

    /** {@code null}이면 필드 자체를 뺀 본문을 만든다(T12). */
    private String body(String ottIdsJson) {
        return ottIdsJson == null ? "{}" : "{\"ottIds\":" + ottIdsJson + "}";
    }

    private int concurrentReplace(
            String ottIdsJson, String token, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return mvc.perform(
                        put(MY_OTTS_PATH)
                                .header("Authorization", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(ottIdsJson)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    private void ott(long id, String code, String name, int tmdbProviderId) {
        jdbc.update(
                "INSERT INTO otts(id,code,name,tmdb_provider_id) VALUES (?,?,?,?)",
                id,
                code,
                name,
                tmdbProviderId);
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

    private void subscribed(long userId, long ottId) {
        jdbc.update("INSERT INTO user_otts(user_id,ott_id) VALUES (?,?)", userId, ottId);
    }

    private List<Long> subscribedIds(long userId) {
        return jdbc.queryForList(
                "SELECT ott_id FROM user_otts WHERE user_id=? ORDER BY ott_id", Long.class, userId);
    }
}
