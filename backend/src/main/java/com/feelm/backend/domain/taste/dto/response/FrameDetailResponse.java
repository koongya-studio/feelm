package com.feelm.backend.domain.taste.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record FrameDetailResponse(
        long frameId, Movie movie, String flavorCode, Rating rating, WatchRecord watchRecord) {

    public record Movie(
            long movieId,
            String title,
            String posterUrl,
            Integer releaseYear,
            List<String> genres,
            List<String> directors) {}

    public record Rating(long ratingId, BigDecimal score, String comment, String visibility) {}

    public record WatchRecord(Ott ott, Instant clickedAt, Instant watchedAt, Instant ratedAt) {}

    public record Ott(String code, String name) {}
}
