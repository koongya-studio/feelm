/*
 * 온보딩 데이터 접근 함수.
 * 대응 명세는 docs/api/onboarding-ott.md이다.
 *
 * 목업을 걷어내고 실제 API를 부른다(업무 S15P21E106-379).
 *
 * **세션 저장소에 더 쓰지 않는다.** 목업 시절에는 제출한 취향을 `mocks/sessionStore`에
 * 남겨 두고 홈 추천 목업이 그것을 읽어 순서를 바꿨는데, 이제 카드가 서버에서 오므로
 * `movieId`가 목업 영화(301~306)와 겹치지 않아 그 계산은 아무 일도 하지 않는다.
 * 추천은 API-045를 연동할 때 서버가 반영한 결과를 그대로 받는다.
 */
import { request } from './client';
import type { OnboardingCard, OnboardingPreference } from '../types/onboarding';
import type { OnboardingSubmitResult } from '../types/onboarding';

/** API-023 응답 */
type OnboardingCardListResponse = { items: OnboardingCard[] };

/**
 * API-023 초기 취향 카드 목록 · GET /api/v1/onboarding/cards
 *
 * 회원 전용이다. `size`를 넘기지 않으면 계약 기본값 20장이며, 화면이 한 번에 늘어놓을
 * 수 있는 장수는 화면이 정한다(1.1은 10장).
 *
 * 이미 온보딩을 마친 사용자는 `409 ONBOARDING_ALREADY_COMPLETED`다. 카드를 고른 뒤에야
 * 거절당하지 않도록 제출이 아니라 조회에서 막는다.
 */
export async function getOnboardingCards(size?: number): Promise<OnboardingCard[]> {
  const response = await request<OnboardingCardListResponse>('/onboarding/cards', {
    query: { size },
  });

  return response.items;
}

/**
 * API-024 초기 취향 제출 · POST /api/v1/onboarding/preferences
 *
 * 명시적으로 선택한 값만 보낸다. `안 봤어요`와 제거한 카드는 배열에 넣지 않는다
 * (처리 규칙 — 제외 카드는 저장하지 않는다).
 * `skipped: true`이면 `preferences`는 빈 배열이어야 한다.
 *
 * 두 필드 모두 필수다. 빠뜨리면 `400 VALIDATION_FAILED`이며, 서버가 기본값으로 채워
 * 주지 않는다 — 건너뛰기 의사를 밝히지 않은 빈 제출이 온보딩을 완료해 버리지 않게
 * 하려는 규칙이다.
 */
export async function submitOnboardingPreferences(
  preferences: OnboardingPreference[],
  skipped: boolean,
): Promise<OnboardingSubmitResult> {
  return request<OnboardingSubmitResult>('/onboarding/preferences', {
    method: 'POST',
    body: { preferences: skipped ? [] : preferences, skipped },
  });
}
