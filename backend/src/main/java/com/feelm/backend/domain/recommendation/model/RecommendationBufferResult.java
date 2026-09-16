package com.feelm.backend.domain.recommendation.model;

/** 생성했거나 동일 후보 버전으로 재사용한 PostgreSQL 추천 세트의 식별자를 반환한다. */
public record RecommendationBufferResult(long recommendationSetId) {}
