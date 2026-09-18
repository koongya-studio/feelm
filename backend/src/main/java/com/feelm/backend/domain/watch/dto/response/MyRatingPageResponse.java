package com.feelm.backend.domain.watch.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "본인 평가·미평가 통합 cursor 페이지")
public record MyRatingPageResponse(
        @Schema(description = "현재 페이지", requiredMode = Schema.RequiredMode.REQUIRED)
                List<MyRatingItemResponse> items,
        @Schema(
                        description = "다음 페이지 cursor. 마지막 페이지면 null",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String nextCursor,
        @Schema(description = "다음 페이지 존재 여부", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean hasNext) {}
