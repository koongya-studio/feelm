package com.feelm.backend.domain.search.dto.response;

import java.util.List;

/** 저장소가 반환하는 검색 행과 cursor 적용 전 전체 결과 수. */
public record MovieSearchQueryResult(List<MovieSearchResponse> results, long totalCount) {}
