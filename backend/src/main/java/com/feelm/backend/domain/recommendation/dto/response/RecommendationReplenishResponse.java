package com.feelm.backend.domain.recommendation.dto.response;

public record RecommendationReplenishResponse(
        long recommendationSetId, RecommendationItemResponse replacement) {}
