package com.feelm.backend.domain.search.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** API-029 전용 응답. 값이 없는 연도도 min/max 필드를 null로 유지한다. */
@Schema(description = "영화 검색 필터 옵션")
public record MovieFilterOptionsResponse(
        @Schema(description = "영화와 연결된 장르 목록", requiredMode = Schema.RequiredMode.REQUIRED)
                List<Option> genres,
        @Schema(description = "영화에 저장된 제작 국가 목록", requiredMode = Schema.RequiredMode.REQUIRED)
                List<Option> countries,
        @Schema(description = "개봉연도 최소·최대 범위", requiredMode = Schema.RequiredMode.REQUIRED)
                YearRange yearRange,
        @Schema(description = "영화와 연결된 지원 OTT 목록", requiredMode = Schema.RequiredMode.REQUIRED)
                List<OttOption> otts) {

    @Schema(description = "코드와 한국어 표시명")
    public record Option(
            @Schema(example = "THRILLER", requiredMode = Schema.RequiredMode.REQUIRED) String code,
            @Schema(example = "스릴러", requiredMode = Schema.RequiredMode.REQUIRED) String name) {}

    @Schema(description = "개봉연도 범위. 날짜가 있는 영화가 없으면 두 값 모두 null")
    public record YearRange(
            @Schema(example = "1920", nullable = true) Integer min,
            @Schema(example = "2026", nullable = true) Integer max) {}

    @Schema(description = "지원 OTT")
    public record OttOption(
            @Schema(example = "1", requiredMode = Schema.RequiredMode.REQUIRED) long ottId,
            @Schema(example = "NETFLIX", requiredMode = Schema.RequiredMode.REQUIRED) String code,
            @Schema(example = "넷플릭스", requiredMode = Schema.RequiredMode.REQUIRED) String name) {}
}
