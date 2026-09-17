package com.feelm.backend.domain.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** API-055·056: 실제 마이그레이션과 PostgreSQL을 사용하는 파티 나가기·종료 HTTP 계약 테스트. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
class PartyLifecycleApiContractTest extends SharedPostgresContractTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired PlatformTransactionManager transactionManager;

    private ExecutorService executor;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        for (long id : List.of(10L, 20L, 30L, 40L, 50L, 60L)) {
            user(id);
        }
    }

    @AfterEach
    void stopExecutor() throws InterruptedException {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void requiresAuthentication() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));

        mvc.perform(post(leavePath(party))).andExpect(status().isUnauthorized());
        mvc.perform(delete(closePath(party))).andExpect(status().isUnauthorized());

        assertThat(memberIds(party)).containsExactly(10L, 20L);
        assertParty(party, 10, "ACTIVE", null);
    }

    @Test
    void memberLeavesActiveParty() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        member(party, 30, "MEMBER", CREATED_AT.plusSeconds(2));

        leave(party, 20)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyId").value(party))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ownerId").value(10))
                .andExpect(jsonPath("$.closedReason").isEmpty())
                .andExpect(jsonPath("$.closedAt").isEmpty());

        assertThat(memberIds(party)).containsExactly(10L, 30L);
        assertParty(party, 10, "ACTIVE", null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM party_refresh_requests WHERE party_id=?",
                                Long.class,
                                party))
                .isEqualTo(1);
    }

    @Test
    void ownerLeavesAndTransfersOwnershipByJoinOrder() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 30, "MEMBER", CREATED_AT.plusSeconds(2));
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        member(party, 40, "MEMBER", CREATED_AT.plusSeconds(1));

        leave(party, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ownerId").value(20));

        assertThat(memberIds(party)).containsExactly(20L, 30L, 40L);
        assertOwner(party, 20);
        assertParty(party, 20, "ACTIVE", null);
    }

    @Test
    void oneRemainingMemberKeepsPartyActiveAndPendingInvitations() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        invitation(party, 10, 30, "PENDING");

        leave(party, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ownerId").value(20))
                .andExpect(jsonPath("$.closedReason").isEmpty())
                .andExpect(jsonPath("$.closedAt").isEmpty());

        assertThat(memberIds(party)).containsExactly(20L);
        assertOwner(party, 20);
        assertInvitation(party, 30, "PENDING", false);
        assertParty(party, 20, "ACTIVE", null);
    }

    @Test
    void lastMemberLeaveClosesPartyAndInvalidatesPendingInvitations() throws Exception {
        long party = party(10, "ACTIVE");
        jdbc.update("INSERT INTO party_refresh_requests(party_id) VALUES (?)", party);
        invitation(party, 10, 20, "PENDING");
        invitation(party, 10, 30, "PENDING");

        leave(party, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.ownerId").value(10))
                .andExpect(jsonPath("$.closedReason").value("INSUFFICIENT_MEMBERS"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());

        assertThat(memberIds(party)).isEmpty();
        assertThat(ownerCount(party)).isZero();
        assertInvitation(party, 20, "DECLINED", true);
        assertInvitation(party, 30, "DECLINED", true);
        assertParty(party, 10, "CLOSED", "INSUFFICIENT_MEMBERS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM party_refresh_requests WHERE party_id=?",
                                Long.class,
                                party))
                .isZero();
    }

    @Test
    void rejectsLeaveForMissingOrNonMemberParty() throws Exception {
        assertLifecycleEndpointsImplemented();
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));

        leave(999_999, 40)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        leave(party, 40)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(memberIds(party)).containsExactly(10L, 20L);
        assertParty(party, 10, "ACTIVE", null);
    }

    @Test
    void rejectsRepeatedLeaveAndClosedPartyLeave() throws Exception {
        long active = party(10, "ACTIVE");
        member(active, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        leave(active, 20).andExpect(status().isOk());
        leave(active, 20)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        long closed = party(30, "CLOSED");
        leave(closed, 30)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTY_CLOSED"));

        assertThat(memberIds(active)).containsExactly(10L);
        assertParty(closed, 30, "CLOSED", "OWNER_CLOSED");
    }

    @Test
    void ownerClosesPartyAndInvalidatesOnlyPendingInvitations() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        invitation(party, 10, 30, "PENDING");
        invitation(party, 10, 40, "ACCEPTED");
        invitation(party, 10, 50, "DECLINED");
        long recommendation = recommendationSet(party);

        close(party, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyId").value(party))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.ownerId").value(10))
                .andExpect(jsonPath("$.closedReason").value("OWNER_CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());

        assertInvitation(party, 30, "DECLINED", true);
        assertInvitation(party, 40, "ACCEPTED", true);
        assertInvitation(party, 50, "DECLINED", true);
        assertThat(memberIds(party)).containsExactly(10L, 20L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM recommendation_sets WHERE id=? AND party_id=?",
                                Long.class,
                                recommendation,
                                party))
                .isEqualTo(1);
    }

    @Test
    void rejectsCloseByMemberOrOutsider() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));

        close(party, 20)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        close(party, 40)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertParty(party, 10, "ACTIVE", null);
    }

    @Test
    void rejectsRepeatedCloseWithoutChangingClosedState() throws Exception {
        long party = party(10, "ACTIVE");
        close(party, 10).andExpect(status().isOk());
        Map<String, Object> firstState = partyState(party);

        close(party, 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTY_CLOSED"));

        assertThat(partyState(party)).isEqualTo(firstState);
    }

    @Test
    void rollsBackLastMemberLeaveWhenInvitationInvalidationFails() throws Exception {
        long party = party(10, "ACTIVE");
        invitation(party, 10, 20, "PENDING");
        jdbc.update("INSERT INTO party_refresh_requests(party_id) VALUES (?)", party);
        addRejectDeclineConstraint();
        try {
            leave(party, 10).andExpect(status().is5xxServerError());
            assertThat(memberIds(party)).containsExactly(10L);
            assertOwner(party, 10);
            assertInvitation(party, 20, "PENDING", false);
            assertParty(party, 10, "ACTIVE", null);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM party_refresh_requests WHERE party_id=?",
                                    Long.class,
                                    party))
                    .isEqualTo(1);
        } finally {
            dropRejectDeclineConstraint();
        }
    }

    @Test
    void rollsBackCloseWhenInvitationInvalidationFails() throws Exception {
        long party = party(10, "ACTIVE");
        invitation(party, 10, 20, "PENDING");
        jdbc.update("INSERT INTO party_refresh_requests(party_id) VALUES (?)", party);
        addRejectDeclineConstraint();
        try {
            close(party, 10).andExpect(status().is5xxServerError());
            assertInvitation(party, 20, "PENDING", false);
            assertParty(party, 10, "ACTIVE", null);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM party_refresh_requests WHERE party_id=?",
                                    Long.class,
                                    party))
                    .isEqualTo(1);
        } finally {
            dropRejectDeclineConstraint();
        }
    }

    @Test
    void serializesConcurrentOwnerLeaveAndClose() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        member(party, 30, "MEMBER", CREATED_AT.plusSeconds(2));

        List<Integer> statuses =
                concurrentRequests(party, 10, RequestType.LEAVE, RequestType.CLOSE);

        assertThat(statuses).contains(200);
        assertThat(statuses).allMatch(status -> status == 200 || status == 404 || status == 409);
        assertThat(statuses.stream().filter(status -> status == 200)).hasSize(1);
        assertActiveOrClosedInvariant(party);
    }

    @Test
    void serializesConcurrentLeavesUntilLastMemberClosesParty() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));

        List<Integer> statuses = concurrentLeaves(party, 10, 20);

        assertThat(statuses).containsExactlyInAnyOrder(200, 200);
        assertThat(memberIds(party)).isEmpty();
        assertThat(ownerCount(party)).isZero();
        Map<String, Object> state = partyState(party);
        assertThat(state.get("owner_id")).isIn(10L, 20L);
        assertThat(state.get("status")).isEqualTo("CLOSED");
        assertThat(state.get("closed_reason")).isEqualTo("INSUFFICIENT_MEMBERS");
        assertThat(state.get("closed_at")).isNotNull();
    }

    @Test
    void waitsForPartyRowLockBeforeLeaving() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> lockHolder =
                executor.submit(
                        () ->
                                new TransactionTemplate(transactionManager)
                                        .executeWithoutResult(
                                                ignored -> {
                                                    jdbc.queryForObject(
                                                            "SELECT id FROM parties WHERE id=? FOR UPDATE",
                                                            Long.class,
                                                            party);
                                                    locked.countDown();
                                                    await(release);
                                                }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        Future<Integer> blockedRequest =
                executor.submit(() -> perform(party, 20, RequestType.LEAVE));
        try {
            assertThatThrownBy(() -> blockedRequest.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
        } finally {
            release.countDown();
        }
        lockHolder.get(5, TimeUnit.SECONDS);
        assertThat(blockedRequest.get(5, TimeUnit.SECONDS)).isEqualTo(200);
    }

    private List<Integer> concurrentRequests(
            long party, long userId, RequestType firstType, RequestType secondType)
            throws Exception {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> first =
                executor.submit(() -> concurrentRequest(party, userId, firstType, ready, start));
        Future<Integer> second =
                executor.submit(() -> concurrentRequest(party, userId, secondType, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }

    private List<Integer> concurrentLeaves(long party, long firstUser, long secondUser)
            throws Exception {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> first =
                executor.submit(
                        () -> concurrentRequest(party, firstUser, RequestType.LEAVE, ready, start));
        Future<Integer> second =
                executor.submit(
                        () ->
                                concurrentRequest(
                                        party, secondUser, RequestType.LEAVE, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }

    private int concurrentRequest(
            long party, long userId, RequestType type, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return perform(party, userId, type);
    }

    private int perform(long party, long userId, RequestType type) throws Exception {
        var request = type == RequestType.LEAVE ? post(leavePath(party)) : delete(closePath(party));
        return mvc.perform(request.header("Authorization", bearer(userId)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private void assertLifecycleEndpointsImplemented() throws Exception {
        long leaveParty = party(10, "ACTIVE");
        member(leaveParty, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        leave(leaveParty, 20).andExpect(status().isOk());

        long closeParty = party(30, "ACTIVE");
        close(closeParty, 30).andExpect(status().isOk());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test latch", exception);
        }
    }

    private ResultActions leave(long party, long userId) throws Exception {
        return mvc.perform(post(leavePath(party)).header("Authorization", bearer(userId)));
    }

    private ResultActions close(long party, long userId) throws Exception {
        return mvc.perform(delete(closePath(party)).header("Authorization", bearer(userId)));
    }

    private String leavePath(long party) {
        return "/api/v1/parties/" + party + "/leave";
    }

    private String closePath(long party) {
        return "/api/v1/parties/" + party;
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    private void user(long id) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                id,
                "party" + id + "@example.test",
                "member" + id);
    }

    private long party(long owner, String status) {
        long id =
                jdbc.queryForObject(
                        "INSERT INTO parties(name,owner_id,status,created_at,closed_reason,closed_at)"
                                + " VALUES ('party',?,?,?,?,?) RETURNING id",
                        Long.class,
                        owner,
                        status,
                        Timestamp.from(CREATED_AT),
                        status.equals("CLOSED") ? "OWNER_CLOSED" : null,
                        status.equals("CLOSED") ? Timestamp.from(CREATED_AT.plusSeconds(1)) : null);
        member(id, owner, "OWNER", CREATED_AT);
        return id;
    }

    private void member(long party, long user, String role, Instant joinedAt) {
        jdbc.update(
                "INSERT INTO party_members(party_id,user_id,role,joined_at) VALUES (?,?,?,?)",
                party,
                user,
                role,
                Timestamp.from(joinedAt));
    }

    private void invitation(long party, long inviter, long invitee, String status) {
        jdbc.update(
                "INSERT INTO party_invitations"
                        + " (party_id,inviter_id,invitee_id,status,created_at,responded_at)"
                        + " VALUES (?,?,?,?,?,?)",
                party,
                inviter,
                invitee,
                status,
                Timestamp.from(CREATED_AT),
                status.equals("PENDING") ? null : Timestamp.from(CREATED_AT.plusSeconds(1)));
    }

    private long recommendationSet(long party) {
        return jdbc.queryForObject(
                "INSERT INTO recommendation_sets"
                        + " (party_id,input_version,model_version,generated_at)"
                        + " VALUES (?,'input-v1','model-v1',?) RETURNING id",
                Long.class,
                party,
                Timestamp.from(CREATED_AT));
    }

    private void addRejectDeclineConstraint() {
        jdbc.execute(
                "ALTER TABLE party_invitations ADD CONSTRAINT test_reject_decline"
                        + " CHECK (status <> 'DECLINED')");
    }

    private void dropRejectDeclineConstraint() {
        jdbc.execute("ALTER TABLE party_invitations DROP CONSTRAINT test_reject_decline");
    }

    private List<Long> memberIds(long party) {
        return jdbc.queryForList(
                "SELECT user_id FROM party_members WHERE party_id=? ORDER BY user_id",
                Long.class,
                party);
    }

    private long ownerCount(long party) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM party_members WHERE party_id=? AND role='OWNER'",
                Long.class,
                party);
    }

    private void assertOwner(long party, long owner) {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT user_id FROM party_members"
                                        + " WHERE party_id=? AND role='OWNER'",
                                Long.class,
                                party))
                .isEqualTo(owner);
        assertThat(ownerCount(party)).isEqualTo(1);
    }

    private void assertInvitation(long party, long invitee, String status, boolean responded) {
        Map<String, Object> invitation =
                jdbc.queryForMap(
                        "SELECT status,responded_at FROM party_invitations"
                                + " WHERE party_id=? AND invitee_id=?",
                        party,
                        invitee);
        assertThat(invitation.get("status")).isEqualTo(status);
        assertThat(invitation.get("responded_at") != null).isEqualTo(responded);
    }

    private Map<String, Object> partyState(long party) {
        return jdbc.queryForMap(
                "SELECT owner_id,status,closed_reason,closed_at FROM parties WHERE id=?", party);
    }

    private void assertParty(long party, long owner, String status, String closedReason) {
        Map<String, Object> state = partyState(party);
        assertThat(state.get("owner_id")).isEqualTo(owner);
        assertThat(state.get("status")).isEqualTo(status);
        assertThat(state.get("closed_reason")).isEqualTo(closedReason);
        assertThat(state.get("closed_at") == null).isEqualTo(closedReason == null);
    }

    private void assertActiveOrClosedInvariant(long party) {
        Map<String, Object> state = partyState(party);
        if (state.get("status").equals("ACTIVE")) {
            assertThat(memberIds(party)).isNotEmpty();
            assertOwner(party, ((Number) state.get("owner_id")).longValue());
            assertThat(state.get("closed_reason")).isNull();
            assertThat(state.get("closed_at")).isNull();
            return;
        }
        assertThat(state.get("status")).isEqualTo("CLOSED");
        assertThat(state.get("closed_reason")).isNotNull();
        assertThat(state.get("closed_at")).isNotNull();
    }

    private enum RequestType {
        LEAVE,
        CLOSE
    }
}
