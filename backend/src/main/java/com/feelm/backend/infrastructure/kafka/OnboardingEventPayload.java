package com.feelm.backend.infrastructure.kafka;

/**
 * 초기 취향 제출 이벤트의 payload다(API-024).
 *
 * <p>개별 선택 내용은 싣지 않는다. Consumer가 최신 평가·관심없음 입력을 다시 읽도록 재계산 신호만 전달한다. 건너뛴 제출은 0건이며 개인 K0 인기순 후보 공급을
 * 시작한다(이벤트·Redis 계약 6.3).
 */
public record OnboardingEventPayload(int preferenceCount) {

    /** 선택 수는 0 이상이며 건너뛴 제출도 허용한다. */
    public OnboardingEventPayload {
        if (preferenceCount < 0) {
            throw new IllegalArgumentException("ONBOARDING preferenceCount must be non-negative");
        }
    }
}
