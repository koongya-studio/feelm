package com.feelm.backend.domain.recommendation.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record RecommendationResponse(
        long recommendationSetId,
        OffsetDateTime generatedAt,
        List<RecommendationItemResponse> items) {}
