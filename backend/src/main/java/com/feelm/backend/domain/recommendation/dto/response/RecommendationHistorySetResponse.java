package com.feelm.backend.domain.recommendation.dto.response;

import java.time.Instant;
import java.util.List;

public record RecommendationHistorySetResponse(
        long recommendationSetId,
        Instant generatedAt,
        List<RecommendationHistoryItemResponse> items) {}
