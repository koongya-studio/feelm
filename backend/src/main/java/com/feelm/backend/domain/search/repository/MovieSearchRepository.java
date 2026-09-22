package com.feelm.backend.domain.search.repository;

import com.feelm.backend.domain.search.cursor.MovieSearchCursor;
import com.feelm.backend.domain.search.dto.request.MovieSearchCriteria;
import com.feelm.backend.domain.search.dto.response.MovieSearchQueryResult;
import java.time.LocalDate;

/** ADR-0010: 검색 엔진의 세부 구현과 유스케이스를 분리한다. */
public interface MovieSearchRepository {
    MovieSearchQueryResult search(
            MovieSearchCriteria criteria,
            MovieSearchCursor cursor,
            Long userId,
            LocalDate releasedThrough);
}
