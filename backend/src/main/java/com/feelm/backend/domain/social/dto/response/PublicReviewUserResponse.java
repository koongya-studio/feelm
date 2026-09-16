package com.feelm.backend.domain.social.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "공개 한줄평 작성자 요약")
public record PublicReviewUserResponse(
        @Schema(
                        description = "작성자 사용자 ID",
                        example = "1024",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                long userId,
        @Schema(
                        description = "작성자 닉네임",
                        example = "팝콘러버",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String nickname) {}
