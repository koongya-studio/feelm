package com.feelm.backend.domain.party.model;

import java.util.List;

/** 한 번의 Redis 읽기로 얻은 파티원별 동일 세대 입력. */
public record PartyInputSnapshot(
        int schemaVersion, String modelVersion, String servingRevision, List<Member> members) {
    public static final int LEGACY_VECTOR_SCHEMA_VERSION = 1;
    public static final int GBT_STATE_SCHEMA_VERSION = 2;

    /** 전환 전 ALS 입력을 읽는 동안 기존 inputId와 파티 요청을 보존한다. */
    public PartyInputSnapshot(String modelVersion, List<Member> members) {
        this(LEGACY_VECTOR_SCHEMA_VERSION, modelVersion, null, members);
    }

    public boolean isGbtState() {
        return schemaVersion == GBT_STATE_SCHEMA_VERSION;
    }

    public record Member(
            long userId,
            String generationId,
            String inputSchemaVersion,
            String inputHash,
            List<Double> legacyVector,
            List<Long> candidateMovieIds) {
        public Member(
                long userId,
                String generationId,
                List<Double> legacyVector,
                List<Long> candidateMovieIds) {
            this(userId, generationId, null, null, legacyVector, candidateMovieIds);
        }

        public static Member gbt(
                long userId,
                String generationId,
                String inputSchemaVersion,
                String inputHash,
                List<Long> candidateMovieIds) {
            return new Member(
                    userId, generationId, inputSchemaVersion, inputHash, null, candidateMovieIds);
        }
    }
}
