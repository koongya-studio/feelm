package com.feelm.backend.domain.taste.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record FrameListResponse(
        long totalCount, List<Item> items, String nextCursor, boolean hasNext) {

    public record Item(
            long frameId, Movie movie, BigDecimal score, String flavorCode, Instant createdAt) {}

    public record Movie(long movieId, String title, String posterUrl) {}
}
