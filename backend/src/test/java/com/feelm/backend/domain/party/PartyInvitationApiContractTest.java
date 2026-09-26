package com.feelm.backend.domain.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.notification.service.NotificationChangedEvent;
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
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** API-051·052: 파티 초대 생성과 받은 초대 목록의 HTTP 계약 테스트. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@RecordApplicationEvents
class PartyInvitationApiContractTest extends SharedPostgresContractTest {
    private static final String LIST_PATH = "/api/v1/parties/invitations";
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired ApplicationEvents events;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactionManager;

    private ExecutorService executor;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        for (long id = 10; id <= 80; id += 10) {
            user(id);
        }
    }

    @AfterEach
    void shutdownExecutor() throws InterruptedException {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_party_notification ON notifications");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_party_notification()");
        if (executor == null) return;
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void requiresAuthentication() throws Exception {
        long party = party(10, "ACTIVE", CREATED_AT);

        mvc.perform(
                        post(invitationPath(party))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"userId\":20}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(LIST_PATH)).andExpect(status().isUnauthorized());
        assertThat(invitationCount()).isZero();
    }

    @Test
    void invitesUserAndReturnsPendingInvitation() throws Exception {
        long party = party(10, "ACTIVE", CREATED_AT);

        JsonNode response =
                body(
                        invite(party, 20, 10)
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.partyId").value(party))
                                .andExpect(jsonPath("$.invitee.userId").value(20))
                                .andExpect(jsonPath("$.invitee.nickname").value("member20"))
                                .andExpect(jsonPath("$.status").value("PENDING"))
                                .andExpect(jsonPath("$.createdAt").isNotEmpty()));

        assertThat(response.get("invitationId").asLong()).isPositive();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT user_id,type,source_id,target_type,target_id,closed_at"
                                        + " FROM notifications"))
                .containsEntry("user_id", 20L)
                .containsEntry("type", "PARTY_INVITE")
                .containsEntry("source_id", response.get("invitationId").asLong())
                .containsEntry("target_type", "PARTY_INVITATION")
                .containsEntry("target_id", response.get("invitationId").asLong())
                .containsEntry("closed_at", null);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT party_id,inviter_id,invitee_id,status,responded_at"
                                        + " FROM party_invitations"))
                .containsEntry("party_id", party)
                .containsEntry("inviter_id", 10L)
                .containsEntry("invitee_id", 20L)
                .containsEntry("status", "PENDING")
                .containsEntry("responded_at", null);
    }

    @Test
    void invitesUserWhoTurnedOffPartyInviteNotificationWithoutNotification() throws Exception {
        // S15P21E106-857: 알림만 빠지고 초대 자체와 받은 초대 목록은 설정과 무관하다
        jdbc.update(
                "INSERT INTO notification_settings(user_id,party_invite_enabled) VALUES (20,false)");
        long party = party(10, "ACTIVE", CREATED_AT);

        invite(party, 20, 10)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_push_deliveries", Long.class))
                .isZero();
        assertThat(
                        events.stream(NotificationChangedEvent.class)
                                .map(NotificationChangedEvent::userId))
                .containsExactly(20L);
        list(20).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].party.partyId").value(party))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"));
    }

    @Test
    void rollsBackInvitationWhenNotificationWriteFails() throws Exception {
        long party = party(10, "ACTIVE", CREATED_AT);
        jdbc.execute(
                """
                CREATE FUNCTION test_fail_party_notification() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'forced notification failure';
                END;
                $$
                """);
        jdbc.execute(
                """
                CREATE TRIGGER test_fail_party_notification
                BEFORE INSERT ON notifications
                FOR EACH ROW EXECUTE FUNCTION test_fail_party_notification()
                """);

        invite(party, 20, 10).andExpect(status().is5xxServerError());

        assertThat(invitationCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
    }

    @Test
    void allowsCurrentNonOwnerMemberToInvite() throws Exception {
        long party = party(10, "ACTIVE", CREATED_AT);
        member(party, 20);

        invite(party, 30, 20).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT inviter_id FROM party_invitations", Long.class))
                .isEqualTo(20);
    }

    @Test
    void allowsInvitingUserWithTenActiveParties() throws Exception {
        for (int i = 0; i < 10; i++) {
            long joinedParty = party(10, "ACTIVE", CREATED_AT.plusSeconds(i));
            member(joinedParty, 20);
        }
        long targetParty = party(30, "ACTIVE", CREATED_AT.plusSeconds(20));

        invite(targetParty, 20, 30).andExpect(status().isCreated());

        assertThat(invitationCount()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM party_invitations WHERE invitee_id=20",
                                String.class))
                .isEqualTo("PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"userId\":null}", "{\"userId\":0}", "{\"userId\":-1}", "{"})
    void rejectsInvalidInvitationRequest(String request) throws Exception {
        long party = party(10, "ACTIVE", CREATED_AT);

        mvc.perform(
                        post(invitationPath(party))
                                .header("Authorization", bearer(10))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(invitationCount()).isZero();
    }

    @Test
    void hidesMissingPartyAndInvitee() throws Exception {
        invite(9999, 20, 10)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        long party = party(10, "ACTIVE", CREATED_AT);
        invite(party, 9999, 10)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(invitationCount()).isZero();
    }

    @Test
    void hidesInactiveInvitees() throws Exception {
        long party = party(10, "ACTIVE", CREATED_AT);
        jdbc.update("UPDATE users SET status='WITHDRAWN' WHERE id=20");
        jdbc.update(
                "UPDATE users SET status='WITHDRAWN', withdrawn_at=? WHERE id=30",
                Timestamp.from(CREATED_AT));

        for (long target : List.of(20L, 30L)) {
            invite(party, target, 10)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
        assertThat(invitationCount()).isZero();
    }

    @Test
    void rejectsWithdrawnRequester() throws Exception {
        long party = party(10, "ACTIVE", CREATED_AT);
        member(party, 20);
        // 탈퇴 1단계는 커밋됐지만 4단계 파티 정리가 아직 멤버십을 지우지 않은 순간(S15P21E106-758).
        jdbc.update(
                "UPDATE users SET status='WITHDRAWN', withdrawn_at=? WHERE id=20",
                Timestamp.from(CREATED_AT));

        invite(party, 30, 20)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(invitationCount()).isZero();
    }

    @Test
    void rejectsWithdrawnRequesterBeforeRevealingParty() throws Exception {
        jdbc.update("UPDATE users SET status='WITHDRAWN' WHERE id=10");

        invite(9999, 20, 10)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(invitationCount()).isZero();
    }

    /** 대상 계정 잠금이 파티 잠금 앞으로 갔어도 오류는 계약의 순서(파티·요청자·종료 → 대상)대로 난다. */
    @Test
    void keepsExistingRejectionOrderAfterLockReorder() throws Exception {
        jdbc.update("UPDATE users SET status='WITHDRAWN' WHERE id=20");
        long closed = party(10, "CLOSED", CREATED_AT);
        invite(closed, 20, 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTY_CLOSED"));

        long active = party(30, "ACTIVE", CREATED_AT.plusSeconds(1));
        invite(active, 20, 10)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(invitationCount()).isZero();
    }

    @Test
    void rejectsRequesterOutsideParty() throws Exception {
        long party = party(10, "ACTIVE", CREATED_AT);

        invite(party, 30, 20)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(invitationCount()).isZero();
    }

    @Test
    void rejectsInvitationToClosedParty() throws Exception {
        long party = party(10, "CLOSED", CREATED_AT);

        invite(party, 20, 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTY_CLOSED"));
        assertThat(invitationCount()).isZero();
    }

    @Test
    void rejectsSelfAndExistingMemberInvitation() throws Exception {
        long party = party(10, "ACTIVE", CREATED_AT);
        member(party, 20);

        for (long target : List.of(10L, 20L)) {
            invite(party, target, 10)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
        }
        assertThat(invitationCount()).isZero();
    }

    @Test
    void rejectsPendingDuplicateButAllowsReinviteAfterDecline() throws Exception {
        long party = party(10, "ACTIVE", CREATED_AT);
        invitation(party, 10, 20, "PENDING", CREATED_AT);

        invite(party, 20, 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_PENDING"));
        jdbc.update(
                "UPDATE party_invitations SET status='DECLINED', responded_at=?",
                Timestamp.from(CREATED_AT.plusSeconds(1)));
        invite(party, 20, 10).andExpect(status().isCreated());

        assertThat(invitationCount()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM party_invitations WHERE status='PENDING'",
                                Long.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsInvitationWhenPartyCapacityIsReserved() throws Exception {
        long party = party(10, "ACTIVE", CREATED_AT);
        member(party, 20);
        invitation(party, 10, 30, "PENDING", CREATED_AT);
        invitation(party, 10, 40, "PENDING", CREATED_AT.plusSeconds(1));

        invite(party, 50, 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTY_FULL"));
        assertThat(invitationCount()).isEqualTo(2);
    }

    @Test
    void rejectsInvitationAtSelectedTwoPersonCapacity() throws Exception {
        long party = party(10, "ACTIVE", CREATED_AT);
        jdbc.update("UPDATE parties SET max_members=2 WHERE id=?", party);
        invitation(party, 10, 20, "PENDING", CREATED_AT);

        invite(party, 30, 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTY_FULL"));
        assertThat(invitationCount()).isEqualTo(1);
    }

    @Test
    void hidesInviteeBlockedInEitherDirection() throws Exception {
        long firstParty = party(10, "ACTIVE", CREATED_AT);
        block(20, 10);
        invite(firstParty, 20, 10)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        jdbc.execute("DELETE FROM user_blocks");
        long secondParty = party(30, "ACTIVE", CREATED_AT.plusSeconds(1));
        member(secondParty, 40);
        block(40, 50);
        invite(secondParty, 50, 30)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(invitationCount()).isZero();
    }

    @Test
    void serializesConcurrentInvitationsAtLastCapacity() throws Exception {
        long party = party(10, "ACTIVE", CREATED_AT);
        member(party, 20);
        invitation(party, 10, 30, "PENDING", CREATED_AT);

        List<InviteResult> results = concurrentInvites(party, 40, 50);

        assertThat(results).extracting(InviteResult::status).containsExactlyInAnyOrder(201, 409);
        assertThat(results)
                .filteredOn(result -> result.status() == 409)
                .singleElement()
                .extracting(InviteResult::code)
                .isEqualTo("PARTY_FULL");
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT count(*) FROM party_members WHERE party_id=?",
                                        Long.class,
                                        party)
                                + invitationCount(party, "PENDING"))
                .isEqualTo(4);
    }

    @Test
    void waitsUntilPartyRowLockIsReleased() throws Exception {
        long party = party(10, "ACTIVE", CREATED_AT);
        LockHold hold =
                holdRowLock("SELECT id FROM parties WHERE id=? FOR UPDATE", party, () -> {});
        Future<InviteResult> blockedRequest =
                executor.submit(() -> performInvite(party, 20, bearer(10)));

        assertBlockedUntilReleased(blockedRequest, hold);

        assertThat(blockedRequest.get(5, TimeUnit.SECONDS).status()).isEqualTo(201);
    }

    /**
     * 대상 계정 행을 잠그고 활성 여부를 본다(S15P21E106-758). 잠금이 풀리기 전에는 초대가 만들어지지 않는다.
     *
     * <p>이 테스트는 명시 잠금을 지워도 통과한다 — 초대 INSERT의 FK 검사가 선잠금 {@code FOR UPDATE}에 막히기 때문이다. 잠금 제거 회귀는 바로
     * 아래 {@link #rejectsInviteeWithdrawnWhileWaitingForRowLock}가 잡는다(리뷰 A-3·B-3).
     */
    @Test
    void waitsUntilInviteeRowLockIsReleased() throws Exception {
        long party = party(10, "ACTIVE", CREATED_AT);
        LockHold hold = holdRowLock("SELECT id FROM users WHERE id=? FOR UPDATE", 20, () -> {});
        Future<InviteResult> blockedRequest =
                executor.submit(() -> performInvite(party, 20, bearer(10)));

        assertBlockedUntilReleased(blockedRequest, hold);

        assertThat(blockedRequest.get(5, TimeUnit.SECONDS).status()).isEqualTo(201);
        assertThat(invitationCount()).isEqualTo(1);
    }

    /** 잠금을 기다리는 동안 대상이 탈퇴하면 잠금 뒤에 다시 판정해 거절한다. 잠금 없는 읽기였다면 ACTIVE를 보고 초대를 만들었을 순간이다. */
    @Test
    void rejectsInviteeWithdrawnWhileWaitingForRowLock() throws Exception {
        long party = party(10, "ACTIVE", CREATED_AT);
        LockHold hold =
                holdRowLock(
                        "SELECT id FROM users WHERE id=? FOR UPDATE",
                        20,
                        () ->
                                jdbc.update(
                                        "UPDATE users SET status='WITHDRAWN', withdrawn_at=now()"
                                                + " WHERE id=20"));
        Future<InviteResult> blockedRequest =
                executor.submit(() -> performInvite(party, 20, bearer(10)));

        assertBlockedUntilReleased(blockedRequest, hold);

        InviteResult result = blockedRequest.get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(404);
        assertThat(result.code()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(invitationCount()).isZero();
    }

    /**
     * 별도 트랜잭션이 행 하나를 {@code FOR UPDATE}로 잠근 채 기다린다. {@code beforeRelease}는 잠금을 놓기 직전에 같은 트랜잭션에서
     * 실행되어 커밋과 함께 보인다.
     */
    private LockHold holdRowLock(String lockSql, long id, Runnable beforeRelease)
            throws InterruptedException {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder =
                executor.submit(
                        () ->
                                new TransactionTemplate(transactionManager)
                                        .executeWithoutResult(
                                                ignored -> {
                                                    jdbc.queryForObject(lockSql, Long.class, id);
                                                    locked.countDown();
                                                    await(release);
                                                    beforeRelease.run();
                                                }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        return new LockHold(holder, release);
    }

    /** 요청이 잠금에 실제로 막혀 있는지 확인한 뒤 잠금을 놓고, 잠금 보유 트랜잭션이 커밋될 때까지 기다린다. */
    private void assertBlockedUntilReleased(Future<InviteResult> blockedRequest, LockHold hold)
            throws Exception {
        try {
            assertThatThrownBy(() -> blockedRequest.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
        } finally {
            hold.release().countDown();
        }
        hold.holder().get(5, TimeUnit.SECONDS);
    }

    private record LockHold(Future<?> holder, CountDownLatch release) {}

    @Test
    void listsPendingInvitationsByDefault() throws Exception {
        long oldest = party(20, "ACTIVE", CREATED_AT);
        member(oldest, 30);
        invitation(oldest, 20, 10, "PENDING", CREATED_AT);
        long newest = party(30, "ACTIVE", CREATED_AT.plusSeconds(2));
        invitation(newest, 30, 10, "PENDING", CREATED_AT.plusSeconds(2));
        invitation(
                party(40, "ACTIVE", CREATED_AT.plusSeconds(3)),
                40,
                10,
                "ACCEPTED",
                CREATED_AT.plusSeconds(3));
        invitation(
                party(50, "ACTIVE", CREATED_AT.plusSeconds(4)),
                50,
                20,
                "PENDING",
                CREATED_AT.plusSeconds(4));

        list(10).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].party.partyId").value(newest))
                .andExpect(jsonPath("$.items[0].party.owner.userId").value(30))
                .andExpect(jsonPath("$.items[0].party.memberCount").value(1))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[1].party.partyId").value(oldest))
                .andExpect(jsonPath("$.items[1].party.memberCount").value(2));
    }

    /** 초대 이력에는 종료된 파티가 포함되고 그 오너가 탈퇴했을 수 있다(S15P21E106-759). 대기 초대 목록은 활성 파티만이라 해당 없다. */
    @Test
    void labelsWithdrawnOwnerInInvitationHistory() throws Exception {
        long closed = party(20, "CLOSED", CREATED_AT);
        invitation(closed, 20, 10, "DECLINED", CREATED_AT);
        long active = party(30, "ACTIVE", CREATED_AT.plusSeconds(1));
        invitation(active, 30, 10, "ACCEPTED", CREATED_AT.plusSeconds(1));
        jdbc.update(
                "UPDATE users SET status='WITHDRAWN', nickname='withdrawn-20', withdrawn_at=?"
                        + " WHERE id=20",
                Timestamp.from(CREATED_AT.plusSeconds(10)));

        mvc.perform(get(LIST_PATH).param("status", "DECLINED").header("Authorization", bearer(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].party.partyId").value(closed))
                .andExpect(jsonPath("$.items[0].party.owner.userId").value(20))
                .andExpect(jsonPath("$.items[0].party.owner.nickname").value("탈퇴한 사용자"));
        mvc.perform(get(LIST_PATH).param("status", "ACCEPTED").header("Authorization", bearer(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].party.owner.userId").value(30))
                .andExpect(jsonPath("$.items[0].party.owner.nickname").value("member30"));
    }

    @Test
    void filtersInvitationsByStatus() throws Exception {
        long party = party(20, "ACTIVE", CREATED_AT);
        invitation(party, 20, 10, "PENDING", CREATED_AT);
        invitation(party, 20, 10, "ACCEPTED", CREATED_AT.plusSeconds(1));
        invitation(party, 20, 10, "DECLINED", CREATED_AT.plusSeconds(2));

        for (String requested : List.of("PENDING", "ACCEPTED", "DECLINED")) {
            mvc.perform(
                            get(LIST_PATH)
                                    .param("status", requested)
                                    .header("Authorization", bearer(10)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].status").value(requested));
        }
    }

    @Test
    void excludesClosedPartyFromPendingInvitations() throws Exception {
        long active = party(20, "ACTIVE", CREATED_AT);
        long closed = party(30, "CLOSED", CREATED_AT.plusSeconds(1));
        invitation(active, 20, 10, "PENDING", CREATED_AT);
        invitation(closed, 30, 10, "PENDING", CREATED_AT.plusSeconds(1));

        list(10).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].party.partyId").value(active));
    }

    @Test
    void paginatesInvitationsWithStableCursor() throws Exception {
        long first = invitation(party(20, "ACTIVE", CREATED_AT), 20, 10, "PENDING", CREATED_AT);
        long second = invitation(party(30, "ACTIVE", CREATED_AT), 30, 10, "PENDING", CREATED_AT);
        long newest =
                invitation(
                        party(40, "ACTIVE", CREATED_AT.plusSeconds(1)),
                        40,
                        10,
                        "PENDING",
                        CREATED_AT.plusSeconds(1));
        List<Long> expected = List.of(newest, second, first);
        String cursor = null;

        for (int page = 0; page < expected.size(); page++) {
            var request = get(LIST_PATH).param("size", "1").header("Authorization", bearer(10));
            if (cursor != null) request.param("cursor", cursor);
            boolean hasNext = page < expected.size() - 1;
            ResultActions result =
                    mvc.perform(request)
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.items", hasSize(1)))
                            .andExpect(
                                    jsonPath("$.items[0].invitationId").value(expected.get(page)))
                            .andExpect(jsonPath("$.hasNext").value(hasNext));
            if (hasNext) {
                cursor = body(result).get("nextCursor").asText();
                assertThat(cursor).isNotBlank();
            } else {
                result.andExpect(jsonPath("$.nextCursor").isEmpty());
            }
        }
    }

    @Test
    void usesDefaultAndMaximumInvitationPageSize() throws Exception {
        for (int i = 0; i < 51; i++) {
            long party = party(20, "ACTIVE", CREATED_AT.plusSeconds(i));
            invitation(party, 20, 10, "PENDING", CREATED_AT.plusSeconds(i));
        }

        list(10).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get(LIST_PATH).param("size", "50").header("Authorization", bearer(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(50)))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "51", "word"})
    void rejectsInvalidInvitationListSize(String size) throws Exception {
        mvc.perform(get(LIST_PATH).param("size", size).header("Authorization", bearer(10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsInvalidInvitationListStatusAndCursor() throws Exception {
        for (String parameter : List.of("status", "cursor")) {
            mvc.perform(
                            get(LIST_PATH)
                                    .param(parameter, "INVALID!")
                                    .header("Authorization", bearer(10)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    /**
     * 형식은 맞지만 커서가 담을 수 있는 범위를 벗어난 시각. 상한을 넘는 값은 질의로 내려가면 드라이버나 DB가 거절해 클라이언트 입력이 500이 되고, 하한 미만은
     * 저장된 생성 시각일 수 없다.
     */
    @Test
    void rejectsUnstorableCursorInstant() throws Exception {
        String[] cursors = {
            forgedCursor("+999999999-12-31T23:59:59Z:1"),
            forgedCursor("+0500000-01-01T00:00:00Z:1"),
            forgedCursor("1900-01-01T00:00:00Z:1")
        };

        for (String cursor : cursors) {
            mvc.perform(get(LIST_PATH).param("cursor", cursor).header("Authorization", bearer(10)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    /** 서버가 만들지 않은 커서를 흉내낸다. 인코딩 형식만 맞추고 값은 임의로 넣는다. */
    private String forgedCursor(String raw) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void returnsEmptyInvitationPage() throws Exception {
        list(10).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.nextCursor").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    private List<InviteResult> concurrentInvites(long party, long firstTarget, long secondTarget)
            throws Exception {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        String token = bearer(10);
        Future<InviteResult> first =
                executor.submit(() -> concurrentInvite(party, firstTarget, token, ready, start));
        Future<InviteResult> second =
                executor.submit(() -> concurrentInvite(party, secondTarget, token, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }

    private InviteResult concurrentInvite(
            long party, long target, String token, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return performInvite(party, target, token);
    }

    private InviteResult performInvite(long party, long target, String token) throws Exception {
        var response =
                mvc.perform(
                                post(invitationPath(party))
                                        .header("Authorization", token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"userId\":" + target + "}"))
                        .andReturn()
                        .getResponse();
        String responseBody = response.getContentAsString();
        String code =
                responseBody.isBlank()
                        ? null
                        : json.readTree(responseBody).path("code").asText(null);
        return new InviteResult(response.getStatus(), code);
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

    private ResultActions invite(long party, long target, long requester) throws Exception {
        return mvc.perform(
                post(invitationPath(party))
                        .header("Authorization", bearer(requester))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + target + "}"));
    }

    private ResultActions list(long userId) throws Exception {
        return mvc.perform(get(LIST_PATH).header("Authorization", bearer(userId)));
    }

    private String invitationPath(long party) {
        return "/api/v1/parties/" + party + "/invitations";
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private void user(long id) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                id,
                "party" + id + "@example.test",
                "member" + id);
    }

    private long party(long owner, String status, Instant createdAt) {
        long id =
                jdbc.queryForObject(
                        "INSERT INTO parties(name,owner_id,status,created_at,closed_reason,closed_at)"
                                + " VALUES ('party',?,?,?,?,?) RETURNING id",
                        Long.class,
                        owner,
                        status,
                        Timestamp.from(createdAt),
                        status.equals("CLOSED") ? "OWNER_CLOSED" : null,
                        status.equals("CLOSED") ? Timestamp.from(createdAt.plusSeconds(1)) : null);
        jdbc.update(
                "INSERT INTO party_members(party_id,user_id,role,joined_at) VALUES (?,?,?,?)",
                id,
                owner,
                "OWNER",
                Timestamp.from(createdAt));
        return id;
    }

    private void member(long party, long user) {
        jdbc.update(
                "INSERT INTO party_members(party_id,user_id,role,joined_at)"
                        + " VALUES (?,?, 'MEMBER',?)",
                party,
                user,
                Timestamp.from(CREATED_AT));
    }

    private long invitation(
            long party, long inviter, long invitee, String status, Instant createdAt) {
        return jdbc.queryForObject(
                "INSERT INTO party_invitations"
                        + " (party_id,inviter_id,invitee_id,status,created_at,responded_at)"
                        + " VALUES (?,?,?,?,?,?) RETURNING id",
                Long.class,
                party,
                inviter,
                invitee,
                status,
                Timestamp.from(createdAt),
                status.equals("PENDING") ? null : Timestamp.from(createdAt.plusSeconds(1)));
    }

    private void block(long blocker, long blocked) {
        jdbc.update(
                "INSERT INTO user_blocks(blocker_id,blocked_id) VALUES (?,?)", blocker, blocked);
    }

    private long invitationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM party_invitations", Long.class);
    }

    private long invitationCount(long party, String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM party_invitations WHERE party_id=? AND status=?",
                Long.class,
                party,
                status);
    }

    private record InviteResult(int status, String code) {}
}
