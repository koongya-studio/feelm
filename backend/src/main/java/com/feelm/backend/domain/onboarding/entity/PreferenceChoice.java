package com.feelm.backend.domain.onboarding.entity;

/**
 * 온보딩 카드에 대한 선택(API-024).
 *
 * <p>좋아요·싫어요 두 값만 있으며 각각 4.0점·2.0점 평가와 프레임·팝콘 집계로도 이어진다. 제외한 카드는 값을 만들지 않고 요청 배열에서 빠진다.
 */
public enum PreferenceChoice {
    LIKE,
    DISLIKE
}
