package com.feelm.backend.domain.watch.dto.response;

import com.feelm.backend.domain.watch.model.RatingVisibility;
import com.feelm.backend.domain.watch.model.WatchStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record RatingCreateResponse(
        long ratingId,
        long movieId,
        BigDecimal score,
        String comment,
        RatingVisibility visibility,
        Frame frame,
        Popcorn popcorn,
        WatchStatus watchStatus,
        Instant createdAt) {

    public record Frame(long frameId, Instant createdAt) {}

    public record Popcorn(String flavorCode, boolean isNewFlavor, long flavorCount) {}
}
