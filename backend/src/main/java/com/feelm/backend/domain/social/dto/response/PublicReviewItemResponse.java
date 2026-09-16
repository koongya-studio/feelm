package com.feelm.backend.domain.social.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "공개 한줄평 목록 항목")
public record PublicReviewItemResponse(
        @Schema(description = "평가 ID", example = "91", requiredMode = Schema.RequiredMode.REQUIRED)
                long ratingId,
        @Schema(description = "작성자 요약", requiredMode = Schema.RequiredMode.REQUIRED)
                PublicReviewUserResponse user,
        @Schema(
                        description = "평점(0.5~5.0, 0.5 단위)",
                        example = "4.5",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                BigDecimal score,
        @Schema(
                        description = "한줄평 원문",
                        example = "스포일러가 포함된 한줄평",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String comment,
        @Schema(
                        description = "로그인 사용자가 작성한 평가인지 여부",
                        example = "false",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                boolean mine,
        @Schema(
                        description = "작성자가 한줄평을 스포일러로 표시했는지 여부",
                        example = "true",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                boolean spoiler,
        @Schema(
                        description = "평가 생성 시각(UTC)",
                        example = "2026-09-16T01:00:00Z",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Instant createdAt) {}
