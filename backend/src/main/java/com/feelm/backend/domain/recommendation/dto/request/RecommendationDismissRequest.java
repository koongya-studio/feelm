package com.feelm.backend.domain.recommendation.dto.request;

/** 추천 카드에서 호출할 때만 카드 식별자를 전달한다. */
public record RecommendationDismissRequest(Long recommendationItemId) {}
