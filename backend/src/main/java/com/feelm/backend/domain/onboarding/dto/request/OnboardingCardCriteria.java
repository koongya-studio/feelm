package com.feelm.backend.domain.onboarding.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * API-023 초기 취향 카드 목록 조회 조건.
 *
 * <p>{@code size}를 넘기지 않으면 명세의 기본값 20이다. 상한 40은 한 번의 온보딩 화면이 보여 줄 수 있는 카드 수이자 장르 조회의 {@code in} 절
 * 크기 상한이다.
 */
public record OnboardingCardCriteria(@Min(1) @Max(40) Integer size) {

    private static final int DEFAULT_SIZE = 20;

    public OnboardingCardCriteria {
        size = size == null ? DEFAULT_SIZE : size;
    }
}
