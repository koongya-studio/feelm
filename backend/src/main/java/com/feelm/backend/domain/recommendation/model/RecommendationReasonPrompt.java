package com.feelm.backend.domain.recommendation.model;

/** LLM에 전달할 수 있는, DB에서 확인한 표시명과 구조화 근거만 담는다. */
public record RecommendationReasonPrompt(
        long itemId, String code, String sourceTitle, String genreName, String directionSource) {}
