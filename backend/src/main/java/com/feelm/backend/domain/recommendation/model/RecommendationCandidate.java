package com.feelm.backend.domain.recommendation.model;

import java.math.BigDecimal;
import tools.jackson.databind.JsonNode;

/** 추천시스템이 Redis 후보 목록에 담은 영화 한 편의 점수와 분류 정보다. */
public record RecommendationCandidate(
        long movieId, BigDecimal score, String type, String flavorCode, JsonNode reason) {}
