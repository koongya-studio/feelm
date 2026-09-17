package com.feelm.backend.domain.onboarding.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * API-023 초기 취향 카드 목록 조회 조건.
 *
 * <p>{@code size}를 넘기지 않으면 확정 영화 전체인 10편을 반환한다. 기존 클라이언트가 목록의 일부만 요청할 수 있도록 파라미터는 유지하되, 확정 목록보다 큰
 * 값은 받지 않는다.
 */
public record OnboardingCardCriteria(@Min(1) @Max(10) Integer size) {

    private static final int DEFAULT_SIZE = 10;

    public OnboardingCardCriteria {
        size = size == null ? DEFAULT_SIZE : size;
    }
}
