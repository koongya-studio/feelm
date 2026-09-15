package com.feelm.backend.domain.search.service;

import com.feelm.backend.domain.search.cursor.MovieSearchCursor;
import com.feelm.backend.domain.search.cursor.MovieSearchCursorCodec;
import com.feelm.backend.domain.search.dto.request.MovieSearchCriteria;
import com.feelm.backend.domain.search.dto.request.MovieSearchSort;
import com.feelm.backend.domain.search.dto.response.MovieSearchPageResponse;
import com.feelm.backend.domain.search.dto.response.MovieSearchQueryResult;
import com.feelm.backend.domain.search.dto.response.MovieSearchResponse;
import com.feelm.backend.domain.search.repository.MovieSearchRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MovieSearchService {

    private final MovieSearchRepository repository;
    private final MovieSearchCursorCodec cursorCodec;

    public MovieSearchPageResponse search(MovieSearchCriteria criteria, Long userId) {
        String criteriaKey = cursorCodec.criteriaKey(criteria);
        MovieSearchCursor cursor =
                criteria.cursor() == null ? null : cursorCodec.decode(criteria.cursor());
        if (cursor != null
                && (cursor.sort() != criteria.sort()
                        || !criteriaKey.equals(cursor.criteriaKey()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        MovieSearchQueryResult queryResult = repository.search(criteria, cursor, userId);
        List<MovieSearchResponse> results = queryResult.results();
        boolean hasNext = results.size() > criteria.size();
        List<MovieSearchResponse> page = hasNext ? results.subList(0, criteria.size()) : results;
        String nextCursor = null;
        if (hasNext) {
            MovieSearchResponse last = page.getLast();
            nextCursor =
                    cursorCodec.encode(
                            new MovieSearchCursor(
                                    criteria.sort(),
                                    last.sortValue(),
                                    criteria.sort() == MovieSearchSort.RELEASE_DATE
                                            ? last.releaseDate()
                                            : null,
                                    last.item().movieId(),
                                    criteriaKey));
        }
        return new MovieSearchPageResponse(
                queryResult.totalCount(),
                page.stream().map(MovieSearchResponse::item).toList(),
                nextCursor,
                hasNext);
    }
}
