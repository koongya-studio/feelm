package com.feelm.backend.domain.taste.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PopcornBucketResponse(long totalCount, List<FlavorBucket> flavors) {

    public record FlavorBucket(
            String flavorCode,
            String displayName,
            String color,
            long popcornCount,
            BigDecimal averageScore,
            Instant discoveredAt) {}
}
