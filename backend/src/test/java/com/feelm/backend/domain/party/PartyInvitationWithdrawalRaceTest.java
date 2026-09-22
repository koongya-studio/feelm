package com.feelm.backend.domain.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import com.feelm.backend.support.SharedRedisContainer;
import com.redis.testcontainers.RedisContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * 파티 초대 생성(API-051)이 회원 탈퇴(API-013)·초대 수락(API-053)과 교차 실행될 때의 계약(S15P21E106-758).
 *
 * <p>실제 PostgreSQL과 마이그레이션 위에서 돌린다. 검증 대상이 계정 행·파티 행 잠금({@code SELECT ... FOR UPDATE})의 대기와 그 뒤의
 * 재판정이기 때문이다. 탈퇴는 HTTP로 부른다 — 1단계 계정 잠금부터 4단계 파티 정리까지 실제 순서로 돌아야 교차 구간이 재현된다. 탈퇴가 리프레시 토큰을 폐기하므로
 * Redis도 띄운다.
 *
 * <p>출발 시각만 맞추면 앞선 요청이 커밋을 끝낸 뒤 다음 요청이 시작될 수 있어 잠금을 없애도 통과한다({@code UserWithdrawalConcurrencyTest}가
 * 같은 이유로 같은 방식을 쓴다). 그래서 테스트가 별도 커넥션으로 행을 먼저 잠그고, 요청을 <b>순서대로</b> 넣어 각 요청이 잠금 대기에 들어간 것을 확인한 뒤
 * 놓아준다. 그 순간부터 두 트랜잭션은 실제로 줄을 선다.
 *
 * <p>{@code @SpringBootTest}의 속성 집합은 {@code PartyWithdrawalApiContractTest}와 똑같이 두어 Spring 컨텍스트와
 * Hikari 풀을 공유한다.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@DisplayName("파티 초대 생성과 탈퇴의 교차 실행 — API-051")
class PartyInvitationWithdrawalRaceTest extends SharedPostgresContractTest {

    @ServiceConnection static final RedisContainer REDIS = SharedRedisContainer.instance();

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");
    private static final String PASSWORD = "P@ssw0rd!42";
    private static final long OWNER = 10;
    private static final long REQUESTER = 20;
    private static final long TARGET = 30;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;

    private ExecutorService executor;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        for (long id : List.of(OWNER, REQUESTER, TARGET)) {
            user(id);
        }
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopExecutor() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * T7. 대상의 탈퇴와 대상을 향한 초대가 같은 계정 행을 두고 경합한다. 어느 쪽이 먼저 잠금을 얻어도 응답할 주체 없는 PENDING 초대는 남지 않는다.
     *
     * <p>PostgreSQL의 튜플 잠금 대기열은 들어간 순서대로 깨어나고 {@link #contend}가 진입 순서를 고정하므로, 순서마다 기대 경로를 단언한다. 탈퇴가
     * 먼저면 초대는 잠금 뒤 {@code WITHDRAWN}을 읽어 {@code 404}, 초대가 먼저면 {@code 201} 뒤 탈퇴 4단계가 목록에서 찾아 {@code
     * DECLINED}로 바꾼다. 두 경로 중 하나로 흘렀다는 느슨한 단언이면 매개화의 의미가 없다(리뷰 A-2·B-2).
     *
     * <p>이 테스트는 불변식(유령 PENDING 없음)을 확인한다. 잠금을 없앤 코드도 {@code inviteFirst}는 물론 {@code
     * withdrawFirst}까지 통과할 수 있다 — 초대 INSERT의 FK 검사가 선잠금에 막혀 탈퇴 1단계 커밋 뒤에 커밋되고 4단계가 회수하기 때문이다. 잠금 제거
     * 회귀는 {@code PartyInvitationApiContractTest.rejectsInviteeWithdrawnWhileWaitingForRowLock}(T5)이
     * 결정적으로 잡는다(리뷰 A-3·B-3).
     */
    @ParameterizedTest(name = "T7 {0}")
    @ValueSource(strings = {"withdrawFirst", "inviteFirst"})
    void concurrentInviteeWithdrawalLeavesNoOrphanPendingInvitation(String order) throws Exception {
        long party = party(OWNER);
        Callable<Response> withdraw = () -> withdraw(TARGET);
        Callable<Response> invite = () -> invite(party, TARGET, OWNER);

        List<Response> results =
                contend(
                        "select id from users where id = ? for update",
                        TARGET,
                        order.equals("withdrawFirst")
                                ? List.of(withdraw, invite)
                                : List.of(invite, withdraw));
        Response withdrawal = results.get(order.equals("withdrawFirst") ? 0 : 1);
        Response invitation = results.get(order.equals("withdrawFirst") ? 1 : 0);

        assertThat(withdrawal.status()).isEqualTo(204);
        assertThat(userStatus(TARGET)).isEqualTo("WITHDRAWN");
        if (order.equals("withdrawFirst")) {
            assertThat(invitation.status()).isEqualTo(404);
            assertThat(invitation.code()).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(invitationCount()).isZero();
        } else {
            assertThat(invitation.status()).isEqualTo(201);
            Map<String, Object> row =
                    jdbc.queryForMap(
                            "select status, responded_at from party_invitations"
                                    + " where party_id = ? and invitee_id = ?",
                            party,
                            TARGET);
            assertThat(row.get("status")).isEqualTo("DECLINED");
            assertThat(row.get("responded_at")).isNotNull();
        }
        assertThat(pendingCount("invitee_id", TARGET)).isZero();
        assertThat(reservedSeats(party)).isEqualTo(1);
    }

    /**
     * T8. 요청자의 탈퇴와 요청자가 보내는 초대가 파티 행을 두고 경합한다. 요청자 판정은 잠금 없이 하므로 초대가 {@code WITHDRAWN} 커밋 뒤에 만들어질 수
     * 있지만, 탈퇴 정리가 파티 행을 잠근 채 멤버십을 지운 뒤 <b>보낸</b> 초대까지 정리하므로 남지 않는다.
     *
     * <p>초대를 먼저 넣어 요청자 판정이 {@code ACTIVE}를 보고 파티 잠금에서 대기하게 만든다. 그 뒤 탈퇴는 1단계를 커밋하고 4단계의 파티 나가기에서 같은
     * 파티 잠금을 기다린다. 대기열 순서대로 초대가 먼저 파티 잠금을 얻으므로 요청자가 이미 {@code WITHDRAWN}인 채 {@code 201}이 나고, 이어
     * 나가기가 멤버십을 지운 뒤 보낸 초대 정리가 그 초대를 {@code DECLINED}로 바꾼다. 이 경로가 결정 5가 말하는 창이므로 결과를 그대로 단언한다(리뷰
     * B-2).
     */
    @Test
    @DisplayName("T8 탈퇴한 요청자가 보낸 초대는 정리 뒤 남지 않는다")
    void concurrentRequesterWithdrawalLeavesNoPendingInvitationSentByWithdrawnUser()
            throws Exception {
        long party = party(OWNER);
        member(party, REQUESTER);

        List<Response> results =
                contend(
                        "select id from parties where id = ? for update",
                        party,
                        List.of(() -> invite(party, TARGET, REQUESTER), () -> withdraw(REQUESTER)));
        Response invitation = results.get(0);
        Response withdrawal = results.get(1);

        assertThat(withdrawal.status()).isEqualTo(204);
        assertThat(userStatus(REQUESTER)).isEqualTo("WITHDRAWN");
        assertThat(invitation.status()).isEqualTo(201);
        Map<String, Object> row =
                jdbc.queryForMap(
                        "select status, responded_at from party_invitations"
                                + " where party_id = ? and inviter_id = ?",
                        party,
                        REQUESTER);
        assertThat(row.get("status")).isEqualTo("DECLINED");
        assertThat(row.get("responded_at")).isNotNull();
        assertThat(pendingCount("inviter_id", REQUESTER)).isZero();
        assertThat(memberIds(party)).containsExactly(OWNER);
        assertThat(jdbc.queryForMap("select owner_id, status from parties where id = ?", party))
                .containsEntry("owner_id", OWNER)
                .containsEntry("status", "ACTIVE");
    }

    /**
     * T9. 초대 수락은 사용자 행 → 파티 행, 초대 생성도 대상 사용자 행 → 파티 행 순서로 잠근다. 같은 사용자의 수락과 재초대가 겹쳐도 교착하지 않는다.
     *
     * <p>선잠금은 <b>대상 사용자 행</b>이다. 수락과 재초대가 모두 그 행에서 순서대로 기다리고, 놓으면 수락이 사용자 → 파티를 끝까지 잡아 커밋한 뒤에야 재초대가
     * 진행하므로 {@code ALREADY_MEMBER}로 결정적이다. 초대 생성이 파티 → 사용자로 잠갔다면 재초대는 파티를 <b>쥔 채</b> 사용자 행을 기다리고
     * 수락은 사용자 행을 얻은 뒤 파티를 기다려 이 배치에서 반드시 교착한다(PostgreSQL이 한쪽을 끊어 {@code 500}). 파티 행을 선잠금하면 뒤집힌
     * 순서에서도 두 요청이 같은 대기열에 서서 교착 없이 통과하므로 회귀를 잡지 못한다(리뷰 B-1).
     */
    @Test
    @DisplayName("T9 같은 사용자의 초대 수락과 재초대가 겹쳐도 교착하지 않는다")
    void concurrentAcceptAndReinviteOfSameUserDoesNotDeadlock() throws Exception {
        long party = party(OWNER);
        long invitationId = invitation(party, OWNER, TARGET);

        List<Response> results =
                contend(
                        "select id from users where id = ? for update",
                        TARGET,
                        List.of(
                                () -> accept(invitationId, TARGET),
                                () -> invite(party, TARGET, OWNER)));

        assertThat(results.get(0).status()).isEqualTo(200);
        assertThat(results.get(1).status()).isEqualTo(409);
        assertThat(results.get(1).code()).isEqualTo("ALREADY_MEMBER");
        assertThat(memberIds(party)).containsExactly(OWNER, TARGET);
        assertThat(pendingCount("invitee_id", TARGET)).isZero();
        assertThat(invitationCount()).isEqualTo(1);
    }

    /**
     * 테스트가 별도 커넥션으로 행을 먼저 잠근 뒤 요청을 순서대로 넣고, 요청마다 잠금 대기가 하나 늘어난 것을 확인한 뒤 잠금을 놓는다.
     *
     * <p>순서대로 넣는 이유는 두 번째 요청이 첫 요청이 쥔 <b>다른</b> 행을 기다리는 경우(T9)에도 대기 수가 정확히 하나씩 늘어나기 때문이다. 대기하지 않으면
     * 경합을 만들지 못한 것이므로 실패시킨다.
     *
     * @return 넘긴 순서 그대로의 응답
     */
    private List<Response> contend(String lockSql, long lockId, List<Callable<Response>> requests)
            throws Exception {
        List<Future<Response>> futures = new ArrayList<>();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(lockSql)) {
                lock.setLong(1, lockId);
                lock.executeQuery();
            }
            for (Callable<Response> request : requests) {
                futures.add(executor.submit(request));
                awaitLockWaiters(futures.size());
            }
            blocker.rollback();
        }
        List<Response> results = new ArrayList<>();
        for (Future<Response> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    private void awaitLockWaiters(int expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(15);
        while (lockWaiterCount() < expected) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException(expected + "개 요청이 행 잠금에서 대기하지 않았다");
            }
            Thread.sleep(20);
        }
    }

    private int lockWaiterCount() {
        return jdbc.queryForObject(
                "select count(*) from pg_stat_activity where wait_event_type = 'Lock'",
                Integer.class);
    }

    private Response withdraw(long userId) throws Exception {
        return perform(
                delete("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"%s\"}".formatted(PASSWORD)));
    }

    private Response invite(long party, long target, long requester) throws Exception {
        return perform(
                post("/api/v1/parties/" + party + "/invitations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(requester))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + target + "}"));
    }

    private Response accept(long invitationId, long userId) throws Exception {
        return perform(
                post("/api/v1/parties/invitations/" + invitationId + "/accept")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId)));
    }

    private Response perform(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        String body = response.getContentAsString();
        String code = body.isBlank() ? null : json.readTree(body).path("code").asText(null);
        return new Response(response.getStatus(), code);
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    /** 탈퇴는 본인 확인을 거치므로 계정에 비밀번호를 넣어 둔다(API-013). */
    private void user(long id) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status,password_hash)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE',?)",
                id,
                "party" + id + "@example.test",
                "member" + id,
                passwordEncoder.encode(PASSWORD));
    }

    private long party(long owner) {
        long id =
                jdbc.queryForObject(
                        "INSERT INTO parties(name,owner_id,status,created_at)"
                                + " VALUES ('party',?,'ACTIVE',?) RETURNING id",
                        Long.class,
                        owner,
                        Timestamp.from(CREATED_AT));
        jdbc.update(
                "INSERT INTO party_members(party_id,user_id,role,joined_at) VALUES (?,?,'OWNER',?)",
                id,
                owner,
                Timestamp.from(CREATED_AT));
        return id;
    }

    private void member(long party, long user) {
        jdbc.update(
                "INSERT INTO party_members(party_id,user_id,role,joined_at) VALUES (?,?,'MEMBER',?)",
                party,
                user,
                Timestamp.from(CREATED_AT.plusSeconds(1)));
    }

    private long invitation(long party, long inviter, long invitee) {
        return jdbc.queryForObject(
                "INSERT INTO party_invitations"
                        + " (party_id,inviter_id,invitee_id,status,created_at,responded_at)"
                        + " VALUES (?,?,?,'PENDING',?,NULL) RETURNING id",
                Long.class,
                party,
                inviter,
                invitee,
                Timestamp.from(CREATED_AT));
    }

    private List<Long> memberIds(long party) {
        return jdbc.queryForList(
                "select user_id from party_members where party_id = ? order by user_id",
                Long.class,
                party);
    }

    private String userStatus(long userId) {
        return jdbc.queryForObject("select status from users where id = ?", String.class, userId);
    }

    private long invitationCount() {
        return jdbc.queryForObject("select count(*) from party_invitations", Long.class);
    }

    /** {@code column}은 테스트 상수({@code invitee_id}·{@code inviter_id})만 넘긴다. */
    private long pendingCount(String column, long userId) {
        return jdbc.queryForObject(
                "select count(*) from party_invitations where "
                        + column
                        + " = ? and status = 'PENDING'",
                Long.class,
                userId);
    }

    /** API-051의 정원 계산과 같은 멤버 + PENDING 초대 수. */
    private long reservedSeats(long party) {
        return jdbc.queryForObject(
                        "select count(*) from party_members where party_id = ?", Long.class, party)
                + jdbc.queryForObject(
                        "select count(*) from party_invitations"
                                + " where party_id = ? and status = 'PENDING'",
                        Long.class,
                        party);
    }

    private record Response(int status, String code) {}
}
