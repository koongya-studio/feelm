package com.feelm.backend.domain.recommendation.dto.response;

import tools.jackson.databind.JsonNode;

/** 관심없음 처리 뒤 같은 유형에서 승격된 추천 카드다. */
public record RecommendationReplacementResponse(
        long recommendationItemId,
        String type,
        RecommendationMovieResponse movie,
        String flavorCode,
        JsonNode reason) {}
