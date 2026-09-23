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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * API-013 회원 탈퇴가 참여 파티에 미치는 영향의 계약 테스트(S15P21E106-436).
 *
 * <p>계정 쪽 계약 테스트({@code UserWithdrawalContractTest})가 아니라 파티 골격에 두는 이유는 확인 대상이 파티 상태이기 때문이다.
 * 파티·멤버·초대 픽스처와 실제 마이그레이션이 필요하고, 그 골격은 여기에 있다. 탈퇴 자체는 HTTP로 부른다 — 진입점 호출이 실제로 배선돼 있는지까지 봐야 한다.
 *
 * <p>규칙 자체(승계 순서·종료 사유·초대 무효화)는 파티 나가기(API-055)의 계약 테스트가 이미 고정한다. 여기서 보는 것은 탈퇴가 그 규칙에 <b>도달하는지</b>와
 * 여러 파티·양방향 초대처럼 탈퇴에만 있는 상황이다.
 *
 * <p>탈퇴(API-013)는 파티 정리에 닿기 전에 리프레시 토큰을 폐기하며 Redis를 거친다. 공유 Redis 연결은 상위 클래스가 제공한다.
 *
 * <p>{@code @SpringBootTest}의 속성 집합은 {@link
 * com.feelm.backend.domain.user.UserWithdrawalConcurrencyTest}· {@code
 * MovieFilterOptionsContractTest}와 똑같이 두었다. 속성이 하나라도 달라지면 Spring 컨텍스트와 Hikari 풀이 따로 생겨 공유
 * PostgreSQL의 연결 상한에 걸리고, 그때 깨지는 것은 이 클래스가 아니라 무관한 테스트다.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@DisplayName("회원 탈퇴의 파티 처리 — API-013")
class PartyWithdrawalApiContractTest extends SharedPostgresContractTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");
    private static final String WITHDRAWAL_PATH = "/api/v1/users/me";
    private static final String PASSWORD = "P@ssw0rd!42";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        for (long id : List.of(10L, 20L, 30L, 40L, 50L)) {
            user(id);
        }
    }

    @Test
    @DisplayName("T1 일반 멤버가 탈퇴하면 그 멤버만 빠지고 파티는 그대로다")
    void removesMemberFromActiveParty() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));

        withdraw(20).andExpect(status().isNoContent());

        assertThat(memberIds(party)).containsExactly(10L);
        assertParty(party, 10, "ACTIVE", null);
    }

    @Test
    @DisplayName("T2 오너가 탈퇴하면 가장 먼저 가입한 멤버가 오너를 잇는다")
    void transfersOwnershipToFirstJoinedMember() throws Exception {
        long party = party(10, "ACTIVE");
        // 식별자 순서와 가입 순서를 엇갈리게 둔다. 둘이 같으면 어느 키로 승계했는지 이 테스트가 가리지 못한다(리뷰 A-3).
        member(party, 30, "MEMBER", CREATED_AT.plusSeconds(1));
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(2));

        withdraw(10).andExpect(status().isNoContent());

        assertThat(memberIds(party)).containsExactly(20L, 30L);
        assertOwner(party, 30);
        assertParty(party, 30, "ACTIVE", null);
    }

    @Test
    @DisplayName("T3 마지막 멤버가 탈퇴하면 파티가 종료되고 대기 초대가 정리된다")
    void closesPartyWhenLastMemberWithdraws() throws Exception {
        long party = party(10, "ACTIVE");
        invitation(party, 10, 30, "PENDING");

        withdraw(10).andExpect(status().isNoContent());

        assertThat(memberIds(party)).isEmpty();
        assertParty(party, 10, "CLOSED", "INSUFFICIENT_MEMBERS");
        assertThat(invitationStatuses(party)).containsExactly("DECLINED");
    }

    @Test
    @DisplayName("T4 참여 중인 활성 파티 전부에서 빠진다")
    void leavesEveryActiveParty() throws Exception {
        long asMember = party(10, "ACTIVE");
        member(asMember, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        long asOwner = party(20, "ACTIVE");
        member(asOwner, 30, "MEMBER", CREATED_AT.plusSeconds(1));
        long alone = party(20, "ACTIVE");

        withdraw(20).andExpect(status().isNoContent());

        assertThat(memberIds(asMember)).containsExactly(10L);
        assertParty(asMember, 10, "ACTIVE", null);
        assertThat(memberIds(asOwner)).containsExactly(30L);
        assertParty(asOwner, 30, "ACTIVE", null);
        assertThat(memberIds(alone)).isEmpty();
        assertParty(alone, 20, "CLOSED", "INSUFFICIENT_MEMBERS");
    }

    @Test
    @DisplayName("T5 탈퇴자가 보낸 대기 초대는 파티가 남아 있어도 거절 처리된다")
    void declinesPendingInvitationsSentByWithdrawnUser() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        invitation(party, 20, 30, "PENDING");

        withdraw(20).andExpect(status().isNoContent());

        // 파티는 10이 남아 활성이다. 초대한 사람이 사라졌으므로 초대만 정리된다.
        assertParty(party, 10, "ACTIVE", null);
        assertThat(invitationStatuses(party)).containsExactly("DECLINED");
        assertThat(respondedAt(party, 30)).isNotNull();
    }

    @Test
    @DisplayName("T6 탈퇴자가 받은 대기 초대도 거절 처리되고 그 파티는 그대로다")
    void declinesPendingInvitationsReceivedByWithdrawnUser() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 40, "MEMBER", CREATED_AT.plusSeconds(1));
        invitation(party, 10, 20, "PENDING");

        withdraw(20).andExpect(status().isNoContent());

        assertThat(memberIds(party)).containsExactly(10L, 40L);
        assertParty(party, 10, "ACTIVE", null);
        assertThat(invitationStatuses(party)).containsExactly("DECLINED");
    }

    @Test
    @DisplayName("T7 이미 종료된 파티의 멤버십은 이력으로 남고 탈퇴는 실패하지 않는다")
    void keepsClosedPartyHistory() throws Exception {
        long closed = party(10, "CLOSED");
        member(closed, 20, "MEMBER", CREATED_AT.plusSeconds(1));

        withdraw(20).andExpect(status().isNoContent());

        assertThat(memberIds(closed)).containsExactly(10L, 20L);
        assertParty(closed, 10, "CLOSED", "OWNER_CLOSED");
    }

    /** 실제 탈퇴 경로가 만든 치환 값이 종료 파티 목록에서 표기 규칙을 타는지 본다(S15P21E106-759). */
    @Test
    @DisplayName("759-T5 종료 파티에 남은 탈퇴자는 목록에서 '탈퇴한 사용자'로 보인다")
    void labelsWithdrawnOwnerInClosedPartyListAfterWithdrawal() throws Exception {
        long closed = party(10, "CLOSED");
        member(closed, 20, "MEMBER", CREATED_AT.plusSeconds(1));

        withdraw(10).andExpect(status().isNoContent());

        mvc.perform(
                        get("/api/v1/parties")
                                .param("status", "CLOSED")
                                .header("Authorization", "Bearer " + tokens.createAccessToken(20)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].partyId").value(closed))
                .andExpect(jsonPath("$.items[0].owner.userId").value(10))
                .andExpect(jsonPath("$.items[0].owner.nickname").value("탈퇴한 사용자"))
                .andExpect(jsonPath("$.items[0].members[0].userId").value(10))
                .andExpect(jsonPath("$.items[0].members[0].nickname").value("탈퇴한 사용자"))
                .andExpect(jsonPath("$.items[0].members[1].userId").value(20))
                .andExpect(jsonPath("$.items[0].members[1].nickname").value("member20"));
        assertThat(userStatus(10)).isEqualTo("WITHDRAWN");
    }

    @Test
    @DisplayName("T8 파티가 없는 계정도 그대로 탈퇴한다")
    void withdrawsWithoutParties() throws Exception {
        withdraw(50).andExpect(status().isNoContent());

        assertThat(userStatus(50)).isEqualTo("WITHDRAWN");
    }

    @Test
    @DisplayName("T9 탈퇴자와 무관한 대기 초대는 건드리지 않는다")
    void keepsOtherUsersInvitationsIntact() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        invitation(party, 10, 30, "PENDING");

        withdraw(20).andExpect(status().isNoContent());

        assertThat(invitationStatuses(party)).containsExactly("PENDING");
    }

    @Test
    @DisplayName("T10 탈퇴자가 더는 멤버가 아닌 파티에 보낸 대기 초대도 정리된다 (리뷰 B-8)")
    void declinesInvitationsSentToPartyTheUserNoLongerBelongsTo() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 40, "MEMBER", CREATED_AT.plusSeconds(1));
        // 20은 이 파티의 멤버가 아니다. 파티를 도는 경로로는 닿지 않고 "보낸 초대" 질의로만 걸린다.
        invitation(party, 20, 30, "PENDING");

        withdraw(20).andExpect(status().isNoContent());

        assertThat(invitationStatuses(party)).containsExactly("DECLINED");
        assertThat(memberIds(party)).containsExactly(10L, 40L);
    }

    @Test
    @DisplayName("T11 정리한 초대의 인앱 알림을 닫는다 (결정 D5)")
    void closesInvitationNotification() throws Exception {
        long party = party(10, "ACTIVE");
        member(party, 20, "MEMBER", CREATED_AT.plusSeconds(1));
        invitation(party, 20, 30, "PENDING");
        long invitationId = invitationId(party, 30);
        invitationNotification(30, invitationId);

        withdraw(20).andExpect(status().isNoContent());

        assertThat(invitationStatuses(party)).containsExactly("DECLINED");
        assertThat(notificationClosedAt(30, invitationId)).isNotNull();
    }

    // ----- 보조 -----

    private long invitationId(long party, long invitee) {
        return jdbc.queryForObject(
                "SELECT id FROM party_invitations WHERE party_id=? AND invitee_id=?",
                Long.class,
                party,
                invitee);
    }

    /** 초대 알림은 알림 도메인이 만들지만 여기서 보는 것은 탈퇴 정리가 그것을 닫는지뿐이라 직접 넣는다. */
    private void invitationNotification(long userId, long invitationId) {
        jdbc.update(
                "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,title,body,"
                        + "notified_at,expires_at) VALUES"
                        + " (?,'PARTY_INVITE',?,'PARTY_INVITATION',?,'초대','본문',now(),now() + interval '90 day')",
                userId,
                invitationId,
                invitationId);
    }

    private Object notificationClosedAt(long userId, long invitationId) {
        return jdbc.queryForObject(
                "SELECT closed_at FROM notifications"
                        + " WHERE user_id=? AND type='PARTY_INVITE' AND source_id=?",
                Object.class,
                userId,
                invitationId);
    }

    private ResultActions withdraw(long userId) throws Exception {
        return mvc.perform(
                delete(WITHDRAWAL_PATH)
                        .header("Authorization", "Bearer " + tokens.createAccessToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"%s\"}".formatted(PASSWORD)));
    }

    /** 탈퇴는 본인 확인을 거치므로 파티 골격의 계정에도 비밀번호를 넣어 둔다(API-013). */
    private void user(long id) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status,password_hash)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE',?)",
                id,
                "party" + id + "@example.test",
                "member" + id,
                passwordEncoder.encode(PASSWORD));
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
                        + " VALUES (?,?,?,?,?,NULL)",
                party,
                inviter,
                invitee,
                status,
                Timestamp.from(CREATED_AT));
    }

    private List<Long> memberIds(long party) {
        return jdbc.queryForList(
                "SELECT user_id FROM party_members WHERE party_id=? ORDER BY user_id",
                Long.class,
                party);
    }

    private List<String> invitationStatuses(long party) {
        return jdbc.queryForList(
                "SELECT status FROM party_invitations WHERE party_id=? ORDER BY id",
                String.class,
                party);
    }

    private Object respondedAt(long party, long invitee) {
        return jdbc.queryForObject(
                "SELECT responded_at FROM party_invitations WHERE party_id=? AND invitee_id=?",
                Object.class,
                party,
                invitee);
    }

    private String userStatus(long userId) {
        return jdbc.queryForObject("SELECT status FROM users WHERE id=?", String.class, userId);
    }

    private void assertOwner(long party, long owner) {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT user_id FROM party_members WHERE party_id=? AND role='OWNER'",
                                Long.class,
                                party))
                .isEqualTo(owner);
    }

    private void assertParty(long party, long owner, String status, String closedReason) {
        Map<String, Object> state =
                jdbc.queryForMap(
                        "SELECT owner_id,status,closed_reason,closed_at FROM parties WHERE id=?",
                        party);
        assertThat(state.get("owner_id")).isEqualTo(owner);
        assertThat(state.get("status")).isEqualTo(status);
        assertThat(state.get("closed_reason")).isEqualTo(closedReason);
        assertThat(state.get("closed_at") == null).isEqualTo(closedReason == null);
    }
}
