package com.feelm.backend.domain.search.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record MovieSearchItemResponse(
        long movieId,
        String title,
        String originalTitle,
        String posterUrl,
        Integer releaseYear,
        List<String> genres,
        BigDecimal averageRating,
        List<String> ottProviders,
        boolean watched) {}
