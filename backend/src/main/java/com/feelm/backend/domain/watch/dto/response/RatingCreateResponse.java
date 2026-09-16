package com.feelm.backend.domain.watch.dto.response;

import com.feelm.backend.domain.watch.model.RatingVisibility;
import com.feelm.backend.domain.watch.model.WatchStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

public record RatingCreateResponse(
        long ratingId,
        long movieId,
        BigDecimal score,
        String comment,
        RatingVisibility visibility,
        @Schema(description = "작성자가 선택한 한줄평 스포일러 여부", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean spoiler,
        Frame frame,
        Popcorn popcorn,
        WatchStatus watchStatus,
        Instant createdAt) {

    public record Frame(long frameId, Instant createdAt) {}

    public record Popcorn(String flavorCode, boolean isNewFlavor, long flavorCount) {}
}
