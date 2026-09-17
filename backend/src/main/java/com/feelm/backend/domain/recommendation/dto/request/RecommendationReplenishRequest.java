package com.feelm.backend.domain.recommendation.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RecommendationReplenishRequest(
        @NotNull @Positive Long recommendationSetId,
        @NotNull @Positive Long recommendationItemId) {}
