package com.feelm.backend.domain.party.dto.response;

import com.feelm.backend.domain.party.entity.PartyInvitation;
import java.time.Instant;

public record ReceivedPartyInvitationResponse(
        long invitationId, PartySummary party, PartyInvitation.Status status, Instant createdAt) {

    public static ReceivedPartyInvitationResponse from(
            PartyInvitation invitation, long memberCount) {
        var party = invitation.getParty();
        return new ReceivedPartyInvitationResponse(
                invitation.getId(),
                new PartySummary(
                        party.getId(),
                        party.getName(),
                        new UserSummary(party.getOwner().getId(), party.getOwner().getNickname()),
                        memberCount),
                invitation.getStatus(),
                invitation.getCreatedAt());
    }

    public record PartySummary(long partyId, String name, UserSummary owner, long memberCount) {}

    public record UserSummary(long userId, String nickname) {}
}
