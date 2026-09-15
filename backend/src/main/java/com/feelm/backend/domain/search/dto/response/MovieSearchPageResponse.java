package com.feelm.backend.domain.search.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** API-028 영화 검색 결과와 정확한 전체 건수. */
@Schema(description = "영화 검색 결과")
public record MovieSearchPageResponse(
        @Schema(
                        description = "현재 검색조건에 일치하는 전체 영화 수",
                        example = "24",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                long totalCount,
        @Schema(description = "현재 페이지의 영화 목록", requiredMode = Schema.RequiredMode.REQUIRED)
                List<MovieSearchItemResponse> items,
        @Schema(description = "다음 페이지 조회용 불투명 커서", nullable = true) String nextCursor,
        @Schema(description = "다음 페이지 존재 여부", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean hasNext) {}
