package com.feelm.backend.domain.party.dto.response;

import com.feelm.backend.domain.party.dto.response.PartyResponse.UserSummary;
import com.feelm.backend.domain.party.entity.PartyInvitation;
import java.time.Instant;

public record PartyInvitationResponse(
        long invitationId,
        long partyId,
        UserSummary invitee,
        PartyInvitation.Status status,
        Instant createdAt) {

    /**
     * 초대 대상은 {@link UserSummary#from}으로 내보낸다. 초대 생성(API-051)은 활성 대상에게만 성공하므로 탈퇴자 표기가 실제로 나올 일은 없지만,
     * 파티 도메인의 사용자 변환을 한 곳에 모아 규칙이 갈라지지 않게 한다(S15P21E106-759).
     */
    public static PartyInvitationResponse from(PartyInvitation invitation) {
        return new PartyInvitationResponse(
                invitation.getId(),
                invitation.getParty().getId(),
                UserSummary.from(invitation.getInvitee()),
                invitation.getStatus(),
                invitation.getCreatedAt());
    }
}
