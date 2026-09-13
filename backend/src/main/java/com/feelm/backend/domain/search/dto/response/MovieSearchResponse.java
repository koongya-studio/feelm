package com.feelm.backend.domain.search.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 검색 결과와 서버 전용 페이지 위치. HTTP 응답에는 item만 포함한다. */
public record MovieSearchResponse(
        MovieSearchItemResponse item, BigDecimal sortValue, LocalDate releaseDate) {}
