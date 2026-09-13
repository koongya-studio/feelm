package com.feelm.backend.domain.search.cursor;

import com.feelm.backend.domain.search.dto.request.MovieSearchSort;
import java.math.BigDecimal;
import java.time.LocalDate;

public record MovieSearchCursor(
        MovieSearchSort sort,
        BigDecimal sortValue,
        LocalDate releaseDate,
        long movieId,
        String criteriaKey) {}
