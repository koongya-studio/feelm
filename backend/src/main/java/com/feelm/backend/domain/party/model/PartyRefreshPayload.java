package com.feelm.backend.domain.party.model;

import java.util.List;

/** GBT 파티 Consumer에 전송하는 §4.3 v2 전용 JSON. */
public record PartyRefreshPayload(
        int schemaVersion,
        long partyId,
        String inputId,
        String modelVersion,
        String servingRevision,
        List<Member> members) {
    public static final int SCHEMA_VERSION = 2;

    public record Member(
            long userId,
            String generationId,
            String inputSchemaVersion,
            String inputHash,
            List<Long> candidateMovieIds) {}
}
