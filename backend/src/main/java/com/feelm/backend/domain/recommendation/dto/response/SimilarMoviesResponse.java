package com.feelm.backend.domain.recommendation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "기준 영화의 콘텐츠 임베딩과 가까운 영화 목록")
public record SimilarMoviesResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "기준 서비스 영화 ID")
                long baseMovieId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "코사인 유사도 내림차순 영화 목록")
                List<SimilarMovieItemResponse> items) {}
