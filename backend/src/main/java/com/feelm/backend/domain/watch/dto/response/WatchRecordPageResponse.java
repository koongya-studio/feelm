package com.feelm.backend.domain.watch.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 공통 cursor page의 API-034 OpenAPI 구체 스키마. */
@Schema(description = "감상 기록 cursor 페이지")
public record WatchRecordPageResponse(
        @Schema(description = "현재 페이지의 감상 기록", requiredMode = Schema.RequiredMode.REQUIRED)
                List<WatchRecordItemResponse> items,
        @Schema(
                        description = "다음 페이지 cursor. 마지막 페이지면 null",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String nextCursor,
        @Schema(description = "다음 페이지 존재 여부", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean hasNext) {}
