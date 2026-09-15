package com.feelm.backend.domain.party.dto.response;

import com.feelm.backend.domain.party.entity.PartyInvitation;
import java.time.Instant;

public record PartyInvitationResponse(
        long invitationId,
        long partyId,
        UserSummary invitee,
        PartyInvitation.Status status,
        Instant createdAt) {

    public static PartyInvitationResponse from(PartyInvitation invitation) {
        return new PartyInvitationResponse(
                invitation.getId(),
                invitation.getParty().getId(),
                new UserSummary(
                        invitation.getInvitee().getId(), invitation.getInvitee().getNickname()),
                invitation.getStatus(),
                invitation.getCreatedAt());
    }

    public record UserSummary(long userId, String nickname) {}
}
