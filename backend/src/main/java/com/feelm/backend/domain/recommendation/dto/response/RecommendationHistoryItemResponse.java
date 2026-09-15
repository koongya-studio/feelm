package com.feelm.backend.domain.recommendation.dto.response;

import tools.jackson.databind.JsonNode;

public record RecommendationHistoryItemResponse(
        long recommendationItemId,
        String type,
        RecommendationHistoryMovieResponse movie,
        JsonNode reason,
        boolean dismissed,
        String watchStatus) {}
