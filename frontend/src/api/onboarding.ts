/*
 * 온보딩 데이터 접근 함수.
 * 대응 명세는 docs/api/onboarding-ott.md이다.
 */
import { mockOnboardingCards } from '../mocks/onboarding';
import { respond } from './mockTransport';
import type { OnboardingCard, OnboardingPreference } from '../types/onboarding';
import type { OnboardingSubmitResult } from '../types/onboarding';

/** API-023 초기 취향 카드 목록 · GET /api/v1/onboarding/cards */
export async function getOnboardingCards(): Promise<OnboardingCard[]> {
  return respond(mockOnboardingCards);
}

/**
 * API-024 초기 취향 제출 · POST /api/v1/onboarding/preferences
 *
 * 명시적으로 선택한 값만 보낸다. `안 봤어요`와 제거한 카드는 배열에 넣지 않는다
 * (처리 규칙 — 제외 카드는 저장하지 않는다).
 * `skipped: true`이면 `preferences`는 빈 배열이어야 한다.
 */
export async function submitOnboardingPreferences(
  preferences: OnboardingPreference[],
  skipped: boolean,
): Promise<OnboardingSubmitResult> {
  const saved = skipped ? [] : preferences;

  return respond({
    onboardingCompleted: true,
    savedCount: saved.length,
    skipped,
  });
}
