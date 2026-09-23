package com.feelm.backend.domain.party.dto.response;

import com.feelm.backend.domain.party.entity.Party;
import com.feelm.backend.domain.party.entity.PartyMember;
import com.feelm.backend.domain.party.entity.PartyStatus;
import com.feelm.backend.domain.user.entity.User;
import java.time.Instant;
import java.util.List;

public record PartyResponse(
        long partyId,
        String name,
        PartyStatus status,
        UserSummary owner,
        List<Member> members,
        int maxMembers,
        Instant createdAt) {
    public static PartyResponse from(Party party, List<Member> members) {
        return new PartyResponse(
                party.getId(),
                party.getName(),
                party.getStatus(),
                UserSummary.from(party.getOwner()),
                members,
                Party.MAX_MEMBERS,
                party.getCreatedAt());
    }

    /**
     * 파티 응답에 실리는 사용자 요약. 파티 도메인의 모든 응답이 사용자를 이 변환으로 내보낸다.
     *
     * <p>탈퇴({@code WITHDRAWN})한 사용자는 {@code nickname}을 {@link #WITHDRAWN_NICKNAME}으로 바꾸고 {@code
     * userId}는 그대로 둔다(S15P21E106-759, API-050·052). 탈퇴는 유일 제약을 풀기 위해 닉네임을 {@code withdrawn-{id}}로
     * 치환하는데, 그 값은 내부용이라 화면에 보일 문구가 아니다. 종료 파티는 멤버십과 마지막 오너를 이력으로 남기므로(API-013) 그 사람이 뒤에 탈퇴하면 종료 파티
     * 목록과 초대 이력에 이 치환 값이 그대로 나갔다.
     *
     * <p>행을 감추지 않고 문구만 바꾸는 이유는 종료 파티가 이력이기 때문이다. 멤버를 빼면 인원이 달라 보이고, 오너가 탈퇴자인 파티는 {@code owner}를 비울
     * 방법이 없다. {@code userId}를 남겨 화면 키와 이력 추적을 유지한다.
     *
     * <p>문구를 파티 도메인에 두는 것은 결정 사항이다(759 플랜 U2). 공개 한줄평의 탈퇴 작성자 표기(API-013 미정 사항)가 같은 문구로 확정되면 그때 공용
     * 위치로 올린다.
     */
    public record UserSummary(long userId, String nickname) {
        /** 탈퇴한 사용자의 표기 문구. 공백이 들어 있어 닉네임 규칙(한글·영문·숫자·밑줄)에 맞지 않으므로 살아 있는 어떤 닉네임과도 겹치지 않는다. */
        public static final String WITHDRAWN_NICKNAME = "탈퇴한 사용자";

        public static UserSummary from(User user) {
            return new UserSummary(user.getId(), displayNickname(user));
        }

        /** 화면에 보일 닉네임. 탈퇴자는 고정 문구, 나머지는 저장된 닉네임 그대로다. */
        static String displayNickname(User user) {
            return user.isWithdrawn() ? WITHDRAWN_NICKNAME : user.getNickname();
        }
    }

    public record Member(long userId, String nickname, PartyMember.Role role, Instant joinedAt) {
        public static Member from(PartyMember member) {
            UserSummary user = UserSummary.from(member.getUser());
            return new Member(
                    user.userId(), user.nickname(), member.getRole(), member.getJoinedAt());
        }
    }
}
