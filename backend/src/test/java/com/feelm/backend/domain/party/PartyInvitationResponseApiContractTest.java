package com.feelm.backend.domain.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.party.controller.PartyController;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/** API-053·054: 파티 초대 수락·거절의 HTTP 및 동시성 계약 테스트. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
class PartyInvitationResponseApiContractTest extends SharedPostgresContractTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired ObjectMapper json;

    private ExecutorService executor;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        for (long id = 10; id <= 200; id += 10) {
            user(id);
        }
    }

    @AfterEach
    void shutdownExecutor() throws InterruptedException {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_accept_notification ON notifications");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_accept_notification()");
        if (executor == null) return;
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void acceptAddsMemberAndMarksInvitationAccepted() throws Exception {
        long party = party(20, "ACTIVE", CREATED_AT);
        long invitation = invitation(party, 20, 10, "PENDING");
        jdbc.update(
                "INSERT INTO web_push_subscriptions(user_id,endpoint,endpoint_fingerprint,"
                        + "p256dh_key,auth_secret,capability_version) VALUES"
                        + " (20,'https://fcm.googleapis.com/fcm/send/party-owner',?,"
                        + "'key','auth',1)",
                "a".repeat(64));
        jdbc.update(
                "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,title,body,"
                        + "notified_at,expires_at) VALUES"
                        + " (10,'PARTY_INVITE',?,'PARTY_INVITATION',?,'제목','본문',?,?)",
                invitation,
                invitation,
                Timestamp.from(CREATED_AT),
                Timestamp.from(CREATED_AT.plusSeconds(90L * 86_400)));

        accept(invitation, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyId").value(party))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[?(@.userId == 10)].role").value("MEMBER"));

        assertInvitation(invitation, "ACCEPTED", true);
        assertThat(memberCount(party, 10)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notifications"
                                        + " WHERE type='PARTY_INVITE' AND source_id=?"
                                        + " AND closed_at IS NOT NULL"
                                        + " AND read_at IS NOT NULL",
                                Long.class,
                                invitation))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT user_id,target_type,target_id,title,body,closed_at"
                                        + " FROM notifications"
                                        + " WHERE type='PARTY_INVITE_ACCEPTED' AND source_id=?",
                                invitation))
                .containsEntry("user_id", 20L)
                .containsEntry("target_type", "PARTY")
                .containsEntry("target_id", party)
                .containsEntry("title", "파티 모드 초대 수락")
                .containsEntry("body", "response10 님이 파티 모드에 참여했어요.")
                .containsEntry("closed_at", null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM web_push_deliveries delivery"
                                        + " JOIN notifications notification"
                                        + " ON notification.id=delivery.notification_id"
                                        + " WHERE notification.type='PARTY_INVITE_ACCEPTED'"
                                        + " AND notification.source_id=? AND delivery.status='PENDING'",
                                Long.class,
                                invitation))
                .isOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM party_refresh_requests WHERE party_id=?",
                                Long.class,
                                party))
                .isEqualTo(1);
    }

    @Test
    void declineOnlyMarksInvitationDeclined() throws Exception {
        long party = party(20, "ACTIVE", CREATED_AT);
        long invitation = invitation(party, 20, 10, "PENDING");
        jdbc.update(
                "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,title,body,"
                        + "notified_at,expires_at) VALUES"
                        + " (10,'PARTY_INVITE',?,'PARTY_INVITATION',?,'제목','본문',?,?)",
                invitation,
                invitation,
                Timestamp.from(CREATED_AT),
                Timestamp.from(CREATED_AT.plusSeconds(90L * 86_400)));

        decline(invitation, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invitationId").value(invitation))
                .andExpect(jsonPath("$.status").value("DECLINED"));

        assertInvitation(invitation, "DECLINED", true);
        assertThat(memberCount(party, 10)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notifications"
                                        + " WHERE source_id=? AND closed_at IS NOT NULL",
                                Long.class,
                                invitation))
                .isEqualTo(1);
    }

    @Test
    void rejectsDeclineFromInactiveInvitee() throws Exception {
        long party = party(20, "ACTIVE", CREATED_AT);
        long invitation = invitation(party, 20, 10, "PENDING");
        jdbc.update(
                "UPDATE users SET status='WITHDRAWN', withdrawn_at=? WHERE id=10",
                Timestamp.from(CREATED_AT.plusSeconds(1)));

        decline(invitation, 10)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertInvitation(invitation, "PENDING", false);
    }

    @Test
    void hidesMissingAndForeignInvitation() throws Exception {
        long party = party(20, "ACTIVE", CREATED_AT);
        long invitation = invitation(party, 20, 10, "PENDING");

        for (String action : List.of("accept", "decline")) {
            respond(9999, action, 10)
                    .andExpect(handler().handlerType(PartyController.class))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
            respond(invitation, action, 30)
                    .andExpect(handler().handlerType(PartyController.class))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }

        assertInvitation(invitation, "PENDING", false);
        assertThat(memberCount(party, 10)).isZero();
    }

    @Test
    void rejectsAlreadyProcessedInvitation() throws Exception {
        long party = party(20, "ACTIVE", CREATED_AT);
        long accepted = invitation(party, 20, 10, "ACCEPTED");
        long declined = invitation(party, 20, 10, "DECLINED");

        for (long invitation : List.of(accepted, declined)) {
            for (String action : List.of("accept", "decline")) {
                respond(invitation, action, 10)
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("INVITATION_NOT_PENDING"));
            }
        }

        assertInvitation(accepted, "ACCEPTED", true);
        assertInvitation(declined, "DECLINED", true);
        assertThat(memberCount(party, 10)).isZero();
    }

    @Test
    void rejectsClosedOrFullPartyOnAccept() throws Exception {
        long closedParty = party(20, "CLOSED", CREATED_AT);
        long closedInvitation = invitation(closedParty, 20, 10, "PENDING");
        accept(closedInvitation, 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTY_CLOSED"));

        long fullParty = party(20, "ACTIVE", CREATED_AT.plusSeconds(1));
        member(fullParty, 30);
        member(fullParty, 40);
        member(fullParty, 50);
        long fullInvitation = invitation(fullParty, 20, 10, "PENDING");
        accept(fullInvitation, 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTY_FULL"));

        assertInvitation(closedInvitation, "PENDING", false);
        assertInvitation(fullInvitation, "PENDING", false);
        assertThat(memberCount(closedParty, 10) + memberCount(fullParty, 10)).isZero();
    }

    @Test
    void rejectsAcceptAtActivePartyLimit() throws Exception {
        for (int index = 0; index < 10; index++) {
            long activeParty = party(20, "ACTIVE", CREATED_AT.plusSeconds(index));
            member(activeParty, 10);
        }
        long targetParty = party(30, "ACTIVE", CREATED_AT.plusSeconds(20));
        long invitation = invitation(targetParty, 30, 10, "PENDING");

        accept(invitation, 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_PARTY_LIMIT_REACHED"));

        assertThat(activePartyCount(10)).isEqualTo(10);
        assertInvitation(invitation, "PENDING", false);
    }

    @Test
    void rejectsAcceptWhenBlockWasAddedAfterInvitation() throws Exception {
        long firstParty = party(20, "ACTIVE", CREATED_AT);
        member(firstParty, 30);
        long firstInvitation = invitation(firstParty, 20, 10, "PENDING");
        block(30, 10);

        accept(firstInvitation, 10)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        jdbc.execute("DELETE FROM user_blocks");
        long secondParty = party(40, "ACTIVE", CREATED_AT.plusSeconds(1));
        long secondInvitation = invitation(secondParty, 40, 10, "PENDING");
        block(10, 40);

        accept(secondInvitation, 10)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertInvitation(firstInvitation, "PENDING", false);
        assertInvitation(secondInvitation, "PENDING", false);
        assertThat(memberCount(firstParty, 10) + memberCount(secondParty, 10)).isZero();
    }

    @Test
    void acceptsAfterBlockWasRemoved() throws Exception {
        long party = party(20, "ACTIVE", CREATED_AT);
        long invitation = invitation(party, 20, 10, "PENDING");
        block(20, 10);
        jdbc.execute("DELETE FROM user_blocks");

        accept(invitation, 10).andExpect(status().isOk());

        assertInvitation(invitation, "ACCEPTED", true);
        assertThat(memberCount(party, 10)).isEqualTo(1);
    }

    @Test
    void serializesConcurrentAcceptAndDecline() throws Exception {
        long party = party(20, "ACTIVE", CREATED_AT);
        long invitation = invitation(party, 20, 10, "PENDING");

        List<ResponseResult> results = concurrentResponses(invitation, "accept", "decline", 10);

        assertThat(results).extracting(ResponseResult::status).containsExactlyInAnyOrder(200, 409);
        assertThat(results)
                .filteredOn(result -> result.status() == 409)
                .singleElement()
                .extracting(ResponseResult::code)
                .isEqualTo("INVITATION_NOT_PENDING");
        String finalStatus = invitationState(invitation).get("status").toString();
        assertThat(finalStatus).isIn("ACCEPTED", "DECLINED");
        assertThat(memberCount(party, 10)).isEqualTo(finalStatus.equals("ACCEPTED") ? 1 : 0);
    }

    @Test
    void keepsActivePartyLimitAcrossConcurrentAccepts() throws Exception {
        for (int index = 0; index < 9; index++) {
            long activeParty = party(20, "ACTIVE", CREATED_AT.plusSeconds(index));
            member(activeParty, 10);
        }
        long firstParty = party(30, "ACTIVE", CREATED_AT.plusSeconds(20));
        long secondParty = party(40, "ACTIVE", CREATED_AT.plusSeconds(21));
        long firstInvitation = invitation(firstParty, 30, 10, "PENDING");
        long secondInvitation = invitation(secondParty, 40, 10, "PENDING");

        List<ResponseResult> results = concurrentAccepts(firstInvitation, secondInvitation, 10);

        assertThat(results).extracting(ResponseResult::status).containsExactlyInAnyOrder(200, 409);
        assertThat(results)
                .filteredOn(result -> result.status() == 409)
                .singleElement()
                .extracting(ResponseResult::code)
                .isEqualTo("ACTIVE_PARTY_LIMIT_REACHED");
        assertThat(activePartyCount(10)).isEqualTo(10);
        assertThat(acceptedInvitationCount(firstInvitation, secondInvitation)).isEqualTo(1);
    }

    @Test
    void acceptsCrossOwnedPartyInvitationsWithoutDeadlock() throws Exception {
        long firstParty = party(10, "ACTIVE", CREATED_AT);
        long secondParty = party(20, "ACTIVE", CREATED_AT.plusSeconds(1));
        long invitationForFirst = invitation(secondParty, 20, 10, "PENDING");
        long invitationForSecond = invitation(firstParty, 10, 20, "PENDING");

        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<ResponseResult> first =
                executor.submit(
                        () -> concurrentResponse(invitationForFirst, "accept", 10, ready, start));
        Future<ResponseResult> second =
                executor.submit(
                        () -> concurrentResponse(invitationForSecond, "accept", 20, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .extracting(ResponseResult::status)
                .containsExactlyInAnyOrder(200, 200);
        assertThat(memberCount(firstParty, 20)).isEqualTo(1);
        assertThat(memberCount(secondParty, 10)).isEqualTo(1);
    }

    @Test
    void keepsActivePartyLimitAcrossCreateAndAccept() throws Exception {
        for (int index = 0; index < 9; index++) {
            long activeParty = party(20, "ACTIVE", CREATED_AT.plusSeconds(index));
            member(activeParty, 10);
        }
        long targetParty = party(30, "ACTIVE", CREATED_AT.plusSeconds(20));
        long invitation = invitation(targetParty, 30, 10, "PENDING");

        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<ResponseResult> accept =
                executor.submit(() -> concurrentResponse(invitation, "accept", 10, ready, start));
        Future<ResponseResult> create = executor.submit(() -> concurrentCreate(10, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<ResponseResult> results =
                List.of(accept.get(10, TimeUnit.SECONDS), create.get(10, TimeUnit.SECONDS));

        assertThat(results).filteredOn(result -> result.status() == 409).hasSize(1);
        assertThat(results)
                .filteredOn(result -> result.status() != 409)
                .singleElement()
                .extracting(ResponseResult::status)
                .isIn(200, 201);
        assertThat(results)
                .filteredOn(result -> result.status() == 409)
                .singleElement()
                .extracting(ResponseResult::code)
                .isEqualTo("ACTIVE_PARTY_LIMIT_REACHED");
        assertThat(activePartyCount(10)).isEqualTo(10);
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT count(*) FROM party_invitations WHERE id=? AND status='ACCEPTED'",
                                        Long.class,
                                        invitation)
                                + jdbc.queryForObject(
                                        "SELECT count(*) FROM parties WHERE owner_id=10 AND name='concurrent'",
                                        Long.class))
                .isEqualTo(1);
    }

    @Test
    void rollsBackAcceptanceWhenMembershipWriteFails() throws Exception {
        long party = party(20, "ACTIVE", CREATED_AT);
        long invitation = invitation(party, 20, 10, "PENDING");
        jdbc.execute(
                "ALTER TABLE party_members ADD CONSTRAINT test_reject_member"
                        + " CHECK (role <> 'MEMBER')");
        try {
            accept(invitation, 10).andExpect(status().is5xxServerError());

            assertInvitation(invitation, "PENDING", false);
            assertThat(memberCount(party, 10)).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM party_refresh_requests WHERE party_id=?",
                                    Long.class,
                                    party))
                    .isZero();
        } finally {
            jdbc.execute("ALTER TABLE party_members DROP CONSTRAINT test_reject_member");
        }
    }

    @Test
    void rollsBackAcceptanceWhenOwnerNotificationWriteFails() throws Exception {
        long party = party(20, "ACTIVE", CREATED_AT);
        long invitation = invitation(party, 20, 10, "PENDING");
        jdbc.update(
                "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,title,body,"
                        + "notified_at,expires_at) VALUES"
                        + " (10,'PARTY_INVITE',?,'PARTY_INVITATION',?,'제목','본문',?,?)",
                invitation,
                invitation,
                Timestamp.from(CREATED_AT),
                Timestamp.from(CREATED_AT.plusSeconds(90L * 86_400)));
        jdbc.execute(
                """
                CREATE FUNCTION test_fail_accept_notification() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.type = 'PARTY_INVITE_ACCEPTED' THEN
                        RAISE EXCEPTION 'forced acceptance notification failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute(
                """
                CREATE TRIGGER test_fail_accept_notification
                BEFORE INSERT ON notifications
                FOR EACH ROW EXECUTE FUNCTION test_fail_accept_notification()
                """);

        accept(invitation, 10).andExpect(status().is5xxServerError());

        assertInvitation(invitation, "PENDING", false);
        assertThat(memberCount(party, 10)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notifications"
                                        + " WHERE type='PARTY_INVITE' AND source_id=?"
                                        + " AND closed_at IS NULL AND read_at IS NULL",
                                Long.class,
                                invitation))
                .isOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notifications"
                                        + " WHERE type='PARTY_INVITE_ACCEPTED' AND source_id=?",
                                Long.class,
                                invitation))
                .isZero();
    }

    private List<ResponseResult> concurrentResponses(
            long invitation, String firstAction, String secondAction, long userId)
            throws Exception {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<ResponseResult> first =
                executor.submit(
                        () -> concurrentResponse(invitation, firstAction, userId, ready, start));
        Future<ResponseResult> second =
                executor.submit(
                        () -> concurrentResponse(invitation, secondAction, userId, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }

    private List<ResponseResult> concurrentAccepts(long first, long second, long userId)
            throws Exception {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<ResponseResult> firstResult =
                executor.submit(() -> concurrentResponse(first, "accept", userId, ready, start));
        Future<ResponseResult> secondResult =
                executor.submit(() -> concurrentResponse(second, "accept", userId, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        return List.of(
                firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS));
    }

    private ResponseResult concurrentResponse(
            long invitation, String action, long userId, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return perform(
                post(responsePath(invitation, action)).header("Authorization", bearer(userId)));
    }

    private ResponseResult concurrentCreate(long userId, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return perform(
                post("/api/v1/parties")
                        .header("Authorization", bearer(userId))
                        .contentType("application/json")
                        .content("{\"name\":\"concurrent\"}"));
    }

    private ResponseResult perform(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        String body = response.getContentAsString();
        String code = body.isBlank() ? null : json.readTree(body).path("code").asText(null);
        return new ResponseResult(response.getStatus(), code);
    }

    private ResultActions accept(long invitation, long userId) throws Exception {
        return respond(invitation, "accept", userId);
    }

    private ResultActions decline(long invitation, long userId) throws Exception {
        return respond(invitation, "decline", userId);
    }

    private ResultActions respond(long invitation, String action, long userId) throws Exception {
        return mvc.perform(
                post(responsePath(invitation, action)).header("Authorization", bearer(userId)));
    }

    private String responsePath(long invitation, String action) {
        return "/api/v1/parties/invitations/" + invitation + "/" + action;
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    private void user(long id) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                id,
                "party-response" + id + "@example.test",
                "response" + id);
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
                "INSERT INTO party_members(party_id,user_id,role,joined_at)"
                        + " VALUES (?,?,'OWNER',?)",
                id,
                owner,
                Timestamp.from(createdAt));
        return id;
    }

    private void member(long party, long user) {
        jdbc.update(
                "INSERT INTO party_members(party_id,user_id,role,joined_at)"
                        + " VALUES (?,?,'MEMBER',?)",
                party,
                user,
                Timestamp.from(CREATED_AT));
    }

    private long invitation(long party, long inviter, long invitee, String status) {
        return jdbc.queryForObject(
                "INSERT INTO party_invitations"
                        + " (party_id,inviter_id,invitee_id,status,created_at,responded_at)"
                        + " VALUES (?,?,?,?,?,?) RETURNING id",
                Long.class,
                party,
                inviter,
                invitee,
                status,
                Timestamp.from(CREATED_AT),
                status.equals("PENDING") ? null : Timestamp.from(CREATED_AT.plusSeconds(1)));
    }

    private void block(long blocker, long blocked) {
        jdbc.update(
                "INSERT INTO user_blocks(blocker_id,blocked_id,created_at) VALUES (?,?,?)",
                blocker,
                blocked,
                Timestamp.from(CREATED_AT));
    }

    private void assertInvitation(long invitation, String status, boolean responded) {
        Map<String, Object> state = invitationState(invitation);
        assertThat(state.get("status")).isEqualTo(status);
        assertThat(state.get("responded_at") != null).isEqualTo(responded);
    }

    private Map<String, Object> invitationState(long invitation) {
        return jdbc.queryForMap(
                "SELECT status,responded_at FROM party_invitations WHERE id=?", invitation);
    }

    private long memberCount(long party, long user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM party_members WHERE party_id=? AND user_id=?",
                Long.class,
                party,
                user);
    }

    private long activePartyCount(long user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM party_members m JOIN parties p ON p.id=m.party_id"
                        + " WHERE m.user_id=? AND p.status='ACTIVE'",
                Long.class,
                user);
    }

    private long acceptedInvitationCount(long first, long second) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM party_invitations WHERE id IN (?,?) AND status='ACCEPTED'",
                Long.class,
                first,
                second);
    }

    private record ResponseResult(int status, String code) {}
}
