package com.feelm.backend.domain.recommendation.dto.response;

import tools.jackson.databind.JsonNode;

public record RecommendationItemResponse(
        long recommendationItemId,
        String type,
        RecommendationMovieResponse movie,
        String flavorCode,
        JsonNode reason) {}
