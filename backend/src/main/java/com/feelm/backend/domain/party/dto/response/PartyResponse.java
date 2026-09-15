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

    public record UserSummary(long userId, String nickname) {
        public static UserSummary from(User user) {
            return new UserSummary(user.getId(), user.getNickname());
        }
    }

    public record Member(long userId, String nickname, PartyMember.Role role, Instant joinedAt) {
        public static Member from(PartyMember member) {
            return new Member(
                    member.getUser().getId(),
                    member.getUser().getNickname(),
                    member.getRole(),
                    member.getJoinedAt());
        }
    }
}
