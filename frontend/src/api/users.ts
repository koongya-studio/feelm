/*
 * 사용자 데이터 접근 함수.
 * 대응 명세는 docs/api/users.md이다.
 */
import { getRatings, isOnboardingSubmitted } from '../mocks/sessionStore';
import { mockMyProfile } from '../mocks/user';
import { respond } from './mockTransport';
import type { MyProfile } from '../types/user';

/**
 * API-014 내 프로필 조회 · GET /api/v1/users/me
 *
 * 기준 수치에 이번 세션의 평가를 더한다. 평가 하나가 프레임 1개와 팝콘 1알을 만든다
 * (FR-07, FR-09).
 */
export async function getMyProfile(): Promise<MyProfile> {
  const added = getRatings().length;

  return respond({
    ...mockMyProfile,
    // 온보딩을 마쳤으면 그 사실도 반영한다
    onboardingCompleted: mockMyProfile.onboardingCompleted || isOnboardingSubmitted(),
    stats: {
      frameCount: mockMyProfile.stats.frameCount + added,
      popcornCount: mockMyProfile.stats.popcornCount + added,
      ratingCount: mockMyProfile.stats.ratingCount + added,
    },
  });
}
