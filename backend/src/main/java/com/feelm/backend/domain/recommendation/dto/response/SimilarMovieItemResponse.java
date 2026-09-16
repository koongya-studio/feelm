package com.feelm.backend.domain.recommendation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "기준 영화와 콘텐츠 임베딩이 가까운 영화")
public record SimilarMovieItemResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "서비스 영화 ID")
                long movieId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "영화 제목") String title,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "포스터 URL. 없으면 null",
                        nullable = true)
                String posterUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "전체 사용자 평균 평점")
                BigDecimal averageRating,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "코사인 유사도. 1에 가까울수록 유사함")
                double similarity) {}
