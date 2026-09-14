package com.feelm.backend.domain.onboarding.dto.response;

/**
 * API-024 초기 취향 제출 응답.
 *
 * <p>{@code savedCount}는 실제로 저장한 선택 수이며 건너뛴 제출은 0이다. {@code onboardingCompleted}는 성공 응답에서 항상 참이다.
 */
public record OnboardingSubmitResponse(
        boolean onboardingCompleted, int savedCount, boolean skipped) {}
