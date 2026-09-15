package com.feelm.backend.domain.party.dto.response;

import com.feelm.backend.domain.party.entity.Party;
import com.feelm.backend.domain.party.entity.Party.ClosedReason;
import com.feelm.backend.domain.party.entity.PartyStatus;
import java.time.Instant;

public record PartyStateResponse(
        long partyId,
        PartyStatus status,
        long ownerId,
        ClosedReason closedReason,
        Instant closedAt) {
    public static PartyStateResponse from(Party party) {
        return new PartyStateResponse(
                party.getId(),
                party.getStatus(),
                party.getOwner().getId(),
                party.getClosedReason(),
                party.getClosedAt());
    }
}
