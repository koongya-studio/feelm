package com.feelm.backend.domain.party.dto.response;

import com.feelm.backend.domain.party.entity.PartyInvitation;

public record PartyInvitationStatusResponse(long invitationId, PartyInvitation.Status status) {
    public static PartyInvitationStatusResponse from(PartyInvitation invitation) {
        return new PartyInvitationStatusResponse(invitation.getId(), invitation.getStatus());
    }
}
