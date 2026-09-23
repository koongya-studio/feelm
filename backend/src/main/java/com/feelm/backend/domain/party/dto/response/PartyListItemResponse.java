package com.feelm.backend.domain.party.dto.response;

import com.feelm.backend.domain.party.dto.response.PartyResponse.AvatarUserSummary;
import com.feelm.backend.domain.party.dto.response.PartyResponse.Member;
import com.feelm.backend.domain.party.dto.response.PartyResponse.UserSummary;
import com.feelm.backend.domain.party.entity.Party;
import com.feelm.backend.domain.party.entity.PartyInvitation;
import com.feelm.backend.domain.party.entity.PartyStatus;
import java.time.Instant;
import java.util.List;

public record PartyListItemResponse(
        long partyId,
        String name,
        PartyStatus status,
        AvatarUserSummary owner,
        List<Member> members,
        int maxMembers,
        List<PendingInvitation> pendingInvitations,
        Instant createdAt) {
    public static PartyListItemResponse from(
            Party party,
            String ownerAvatarUrl,
            List<Member> members,
            List<PendingInvitation> invitations) {
        return new PartyListItemResponse(
                party.getId(),
                party.getName(),
                party.getStatus(),
                AvatarUserSummary.from(party.getOwner(), ownerAvatarUrl),
                members,
                Party.MAX_MEMBERS,
                invitations,
                party.getCreatedAt());
    }

    public record PendingInvitation(long invitationId, InviteeSummary invitee, Instant createdAt) {
        public static PendingInvitation from(PartyInvitation invitation) {
            return new PendingInvitation(
                    invitation.getId(), InviteeSummary.from(invitation), invitation.getCreatedAt());
        }
    }

    public record InviteeSummary(long userId, String nickname) {
        private static InviteeSummary from(PartyInvitation invitation) {
            UserSummary invitee = UserSummary.from(invitation.getInvitee());
            return new InviteeSummary(invitee.userId(), invitee.nickname());
        }
    }
}
