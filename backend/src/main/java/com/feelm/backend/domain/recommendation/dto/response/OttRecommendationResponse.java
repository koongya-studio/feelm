package com.feelm.backend.domain.recommendation.dto.response;

import java.time.Instant;
import java.util.List;

/** API-068 OTT 구독 추천 응답. 편수는 현재 TASTE 후보 최대 500편 안에서 계산한다. */
public record OttRecommendationResponse(
        boolean insufficientData, Instant aggregatedAt, List<Item> items) {

    public record Item(Ott ott, int matchCount, boolean subscribed, List<Movie> topMovies) {}

    public record Ott(long ottId, String code, String name, String logoUrl) {}

    public record Movie(long movieId, String title, String posterUrl, Integer releaseYear) {}
}
