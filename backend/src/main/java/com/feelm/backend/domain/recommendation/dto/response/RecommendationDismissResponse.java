package com.feelm.backend.domain.recommendation.dto.response;

/** API-047 관심없음 저장 결과다. 카드 보충은 다음 API-045 조회가 담당한다. */
public record RecommendationDismissResponse(long movieId) {}
