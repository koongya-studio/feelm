package com.feelm.backend.domain.party.model;

import java.util.List;

/** GBT 파티 Consumer에 전송하는 §4.3 입력 참조 JSON. */
public record PartyRefreshPayload(long partyId, String inputId, List<Member> members) {

    public record Member(
            long userId,
            String generationId,
            String inputSchemaVersion,
            String inputHash,
            List<Long> candidateMovieIds) {}
}
