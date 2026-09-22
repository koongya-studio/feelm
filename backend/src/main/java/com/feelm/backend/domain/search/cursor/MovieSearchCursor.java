package com.feelm.backend.domain.search.cursor;

import com.feelm.backend.domain.search.dto.request.MovieSearchSort;
import java.math.BigDecimal;
import java.time.LocalDate;

/** releaseDate는 개봉일 정렬 위치이며, 검색 홈 RATING cursor에서는 최초 한국 개봉 기준일이다. */
public record MovieSearchCursor(
        MovieSearchSort sort,
        BigDecimal sortValue,
        LocalDate releaseDate,
        long movieId,
        String criteriaKey) {}
