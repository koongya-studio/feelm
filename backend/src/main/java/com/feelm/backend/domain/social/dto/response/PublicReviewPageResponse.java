package com.feelm.backend.domain.social.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "공개 한줄평 cursor 페이지")
public record PublicReviewPageResponse(
        @Schema(description = "현재 페이지의 한줄평", requiredMode = Schema.RequiredMode.REQUIRED)
                List<PublicReviewItemResponse> items,
        @Schema(
                        description = "다음 페이지 cursor. 마지막 페이지면 null",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String nextCursor,
        @Schema(description = "다음 페이지 존재 여부", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean hasNext) {}
