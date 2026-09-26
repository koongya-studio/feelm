package com.feelm.backend.domain.recommendation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record DismissalListItemResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long dismissalId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DismissalMovieResponse movie,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant dismissedAt) {
    public record DismissalMovieResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long movieId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
                    String posterUrl) {}
}
