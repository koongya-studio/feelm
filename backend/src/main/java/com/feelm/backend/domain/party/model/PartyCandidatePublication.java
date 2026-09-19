package com.feelm.backend.domain.party.model;

import java.time.Instant;

/** Redis 파티 후보 게시 완료 알림의 검증된 식별 정보다. */
public record PartyCandidatePublication(
        long partyId,
        String inputId,
        String modelVersion,
        Instant generatedAt,
        int sourcePartition,
        long sourceOffset) {}
