package com.feelm.backend.domain.party.model;

import java.time.Instant;
import java.util.List;

/** Spark가 Redis에 게시한 파티 후보 결과 중 Spring이 소비하는 필드다. */
public record PartyCandidateSnapshot(
        long partyId,
        String modelVersion,
        String algorithmVersion,
        String inputId,
        Instant generatedAt,
        List<Candidate> candidates,
        String recommendationMode) {
    public PartyCandidateSnapshot(
            long partyId,
            String modelVersion,
            String algorithmVersion,
            String inputId,
            Instant generatedAt,
            List<Candidate> candidates) {
        this(
                partyId,
                modelVersion,
                algorithmVersion,
                inputId,
                generatedAt,
                candidates,
                "PERSONALIZED");
    }

    public record Candidate(long movieId) {}
}
