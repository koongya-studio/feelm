package com.feelm.backend.domain.recommendation.model;

import java.time.Instant;
import java.util.List;

/** 추천시스템이 한 사용자의 Redis 키에 저장한 후보 JSON 전체를 표현한다. */
public record RecommendationCandidateBatch(
        String modelVersion,
        Long candidateVersion,
        String generationId,
        Instant updatedAt,
        List<RecommendationCandidate> items) {}
