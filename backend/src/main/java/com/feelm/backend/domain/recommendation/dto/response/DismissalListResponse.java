package com.feelm.backend.domain.recommendation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record DismissalListResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<DismissalListItemResponse> items,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) String nextCursor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasNext) {}
