package com.feelm.backend.infrastructure.kafka;

/**
 * 초기 취향 제출 이벤트의 payload다(API-024).
 *
 * <p>개별 선택 내용은 싣지 않는다. Consumer가 {@code onboarding_preferences}를 직접 읽어 Fold-in을 다시 계산하므로, 이벤트는 "이
 * 사용자의 온보딩 입력이 생겼다"는 사실만 전달한다(이벤트·Redis 계약 4.3).
 */
public record OnboardingEventPayload(int preferenceCount) {

    /** 읽을 행이 없는 이벤트는 Consumer가 할 일이 없으므로 발행 자체를 막는다. */
    public OnboardingEventPayload {
        if (preferenceCount <= 0) {
            throw new IllegalArgumentException("ONBOARDING event must carry at least one choice");
        }
    }
}
