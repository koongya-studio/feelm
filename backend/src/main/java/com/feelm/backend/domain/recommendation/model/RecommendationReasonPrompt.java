package com.feelm.backend.domain.recommendation.model;

/**
 * LLM에 전달할 수 있는, DB에서 확인한 표시명과 구조화 근거만 담는다.
 *
 * <p>`historyOnly`는 취향 발견 근거가 확인됐지만 기여가 여러 영화에 퍼져 있어 영화명 대신 전체 평가 이력 문구를 써야 한다는
 * 뜻이다(S15P21E106-925).
 */
public record RecommendationReasonPrompt(
        long itemId,
        String code,
        String sourceTitle,
        String genreName,
        String directionSource,
        boolean historyOnly) {}
