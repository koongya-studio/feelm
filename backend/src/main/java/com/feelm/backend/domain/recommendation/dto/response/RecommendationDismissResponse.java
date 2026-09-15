package com.feelm.backend.domain.recommendation.dto.response;

/** API-047 관심없음 처리 결과다. */
public record RecommendationDismissResponse(
        long movieId, RecommendationReplacementResponse replacement) {}
