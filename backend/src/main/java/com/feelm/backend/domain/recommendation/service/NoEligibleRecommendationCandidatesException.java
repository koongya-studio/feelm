package com.feelm.backend.domain.recommendation.service;

/** 유효한 Redis 후보에서 현재 사용자에게 저장 가능한 영화가 하나도 남지 않은 상태다. */
class NoEligibleRecommendationCandidatesException extends RuntimeException {}
