package com.feelm.backend.domain.party.dto.response;

import com.feelm.backend.domain.party.dto.response.PartyResponse.UserSummary;
import com.feelm.backend.domain.party.entity.PartyInvitation;
import java.time.Instant;

public record ReceivedPartyInvitationResponse(
        long invitationId, PartySummary party, PartyInvitation.Status status, Instant createdAt) {

    /**
     * 오너는 {@link UserSummary#from}으로 내보낸다. {@code ACCEPTED}·{@code DECLINED} 이력에는 종료된 파티가 포함되고 그
     * 오너가 탈퇴했을 수 있어, 탈퇴자 표기 규칙(S15P21E106-759)이 여기에도 적용된다.
     */
    public static ReceivedPartyInvitationResponse from(
            PartyInvitation invitation, long memberCount) {
        var party = invitation.getParty();
        return new ReceivedPartyInvitationResponse(
                invitation.getId(),
                new PartySummary(
                        party.getId(),
                        party.getName(),
                        UserSummary.from(party.getOwner()),
                        memberCount),
                invitation.getStatus(),
                invitation.getCreatedAt());
    }

    public record PartySummary(long partyId, String name, UserSummary owner, long memberCount) {}
}
