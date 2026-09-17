package com.feelm.backend.domain.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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

/** API-069: 실제 마이그레이션과 PostgreSQL을 사용하는 파티원 강퇴 HTTP 계약 테스트. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
class PartyKickApiContractTest extends SharedPostgresContractTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;

    private ExecutorService executor;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users, popcorn_flavors RESTART IDENTITY CASCADE");
        user(10, "owner");
        user(20, "member20");
        user(30, "member30");
        user(40, "outsider");
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

        mvc.perform(delete(path(party, 20))).andExpect(status().isUnauthorized());
        mvc.perform(delete(path(party, 20)).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());

        assertThat(memberIds(party)).containsExactlyInAnyOrder(10L, 20L);
        assertParty(party, 10, "ACTIVE", null);
    }

    @Test
    void ownerKicksMemberAndKeepsPartyActive() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        member(party, 30, "MEMBER", CREATED_AT.plusSeconds(2));

        kick(party, 20, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyId").value(party))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ownerId").value(10))
                .andExpect(jsonPath("$.closedReason").isEmpty())
                .andExpect(jsonPath("$.closedAt").isEmpty());

        assertThat(memberIds(party)).containsExactlyInAnyOrder(10L, 30L);
        assertParty(party, 10, "ACTIVE", null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM party_refresh_requests WHERE party_id=?",
                                Long.class,
                                party))
                .isEqualTo(1);
    }

    @Test
    void keepsTwoMemberPartyActiveAfterKickAndPreservesPendingInvitations() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        invitation(party, 30, "PENDING");
        invitation(party, 40, "PENDING");

        kick(party, 20, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyId").value(party))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ownerId").value(10))
                .andExpect(jsonPath("$.closedReason").isEmpty())
                .andExpect(jsonPath("$.closedAt").isEmpty());

        assertThat(memberIds(party)).containsExactly(10L);
        assertParty(party, 10, "ACTIVE", null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM party_invitations"
                                        + " WHERE party_id=? AND status='PENDING'"
                                        + " AND responded_at IS NULL",
                                Long.class,
                                party))
                .isEqualTo(2);
    }

    @Test
    void hidesPartyFromOutsider() throws Exception {
        assertKickEndpointImplemented();
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));

        kick(party, 20, 40)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(memberIds(party)).containsExactlyInAnyOrder(10L, 20L);
        assertParty(party, 10, "ACTIVE", null);
    }

    @Test
    void rejectsNonOwnerMember() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        member(party, 30, "MEMBER", CREATED_AT.plusSeconds(2));

        kick(party, 30, 20)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(memberIds(party)).containsExactlyInAnyOrder(10L, 20L, 30L);
    }

    @Test
    void rejectsSelfKick() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));

        kick(party, 10, 10)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(memberIds(party)).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void rejectsMissingCurrentMember() throws Exception {
        assertKickEndpointImplemented();
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));

        kick(party, 30, 10)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(memberIds(party)).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void rejectsClosedPartyBeforeTargetValidation() throws Exception {
        long party = party(10, "CLOSED");

        for (long target : new long[] {10, 30}) {
            kick(party, target, 10)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PARTY_CLOSED"));
        }

        assertThat(memberIds(party)).containsExactly(10L);
        assertParty(party, 10, "CLOSED", "OWNER_CLOSED");
    }

    @Test
    void serializesConcurrentKicksInSameParty() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        member(party, 30, "MEMBER", CREATED_AT.plusSeconds(2));

        List<Integer> statuses = concurrentKicks(party, 20, 30);

        assertThat(statuses).containsExactlyInAnyOrder(200, 200);
        assertThat(memberIds(party)).containsExactly(10L);
        assertParty(party, 10, "ACTIVE", null);
    }

    @Test
    void allowsOnlyOneConcurrentKickOfSameMember() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        member(party, 30, "MEMBER", CREATED_AT.plusSeconds(2));

        List<Integer> statuses = concurrentKicks(party, 20, 20);

        assertThat(statuses).containsExactlyInAnyOrder(200, 404);
        assertThat(memberIds(party)).containsExactlyInAnyOrder(10L, 30L);
        assertParty(party, 10, "ACTIVE", null);
    }

    @Test
    void doesNotCreateBlockOrDeleteRecommendationHistory() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        member(party, 30, "MEMBER", CREATED_AT.plusSeconds(2));
        long recommendation = recommendationSet(party);

        kick(party, 20, 10).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_blocks", Long.class)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM recommendation_sets WHERE id=? AND party_id=?",
                                Long.class,
                                recommendation,
                                party))
                .isEqualTo(1);
    }

    @Test
    void excludesKickedMemberFromPartyPopcorn() throws Exception {
        for (int order = 1; order <= 8; order++) {
            flavor(order);
        }
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        member(party, 30, "MEMBER", CREATED_AT.plusSeconds(2));

        kick(party, 20, 10).andExpect(status().isOk());

        mvc.perform(
                        get("/api/v1/parties/" + party + "/popcorn")
                                .header("Authorization", bearer(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members", hasSize(2)))
                .andExpect(jsonPath("$.members[?(@.user.userId == 20)]", hasSize(0)));
    }

    private List<Integer> concurrentKicks(long party, long firstTarget, long secondTarget)
            throws Exception {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        String token = bearer(10);
        Future<Integer> first =
                executor.submit(() -> concurrentKick(party, firstTarget, token, ready, start));
        Future<Integer> second =
                executor.submit(() -> concurrentKick(party, secondTarget, token, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }

    private void assertKickEndpointImplemented() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        member(party, 30, "MEMBER", CREATED_AT.plusSeconds(2));
        kick(party, 20, 10).andExpect(status().isOk());
    }

    private int concurrentKick(
            long party, long target, String token, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return mvc.perform(delete(path(party, target)).header("Authorization", token))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private ResultActions kick(long party, long target, long requester) throws Exception {
        return mvc.perform(delete(path(party, target)).header("Authorization", bearer(requester)));
    }

    private String path(long party, long target) {
        return "/api/v1/parties/" + party + "/members/" + target;
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    private void user(long id, String nickname) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                id,
                nickname + "@example.test",
                nickname);
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

    private void invitation(long party, long invitee, String status) {
        jdbc.update(
                "INSERT INTO party_invitations"
                        + " (party_id,inviter_id,invitee_id,status,created_at,responded_at)"
                        + " VALUES (?,10,?,?,?,?)",
                party,
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

    private void flavor(int order) {
        jdbc.update(
                "INSERT INTO popcorn_flavors"
                        + " (id,code,display_name,color,sort_order,cluster_id,representative_keywords)"
                        + " VALUES (?,?,?,'#000000',?,?,CAST('[]' AS jsonb))",
                (long) order,
                "FLAVOR_" + order,
                "FLAVOR_" + order,
                order,
                order - 1);
    }

    private List<Long> memberIds(long party) {
        return jdbc.queryForList(
                "SELECT user_id FROM party_members WHERE party_id=? ORDER BY user_id",
                Long.class,
                party);
    }

    private void assertParty(long party, long owner, String status, String closedReason) {
        var state =
                jdbc.queryForMap(
                        "SELECT owner_id,status,closed_reason,closed_at FROM parties WHERE id=?",
                        party);
        assertThat(state.get("owner_id")).isEqualTo(owner);
        assertThat(state.get("status")).isEqualTo(status);
        assertThat(state.get("closed_reason")).isEqualTo(closedReason);
        assertThat(state.get("closed_at") == null).isEqualTo(closedReason == null);
    }
}
