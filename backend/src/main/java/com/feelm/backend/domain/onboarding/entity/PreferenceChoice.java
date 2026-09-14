package com.feelm.backend.domain.onboarding.entity;

/**
 * 온보딩 카드에 대한 선택(API-024).
 *
 * <p>감상 후 평가(0.5~5.0점)와 달리 좋아요·싫어요 두 값만 있고 프레임·팝콘 집계에 들어가지 않는다. 제외한 카드는 값을 만들지 않고 요청 배열에서 빠진다.
 */
public enum PreferenceChoice {
    LIKE,
    DISLIKE
}
