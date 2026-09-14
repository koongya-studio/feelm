package com.feelm.backend.domain.recommendation.dto.response;

import java.math.BigDecimal;
import java.util.List;

/** API-045·047이 공유하는 추천 카드의 영화 요약이다. */
public record RecommendationMovieResponse(
        long movieId,
        String title,
        String posterUrl,
        Integer releaseYear,
        List<String> genres,
        BigDecimal averageRating,
        List<String> ottProviders) {}
