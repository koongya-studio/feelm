/*
 * 목업 사용자 프로필. API-014 내 프로필 조회
 *
 * 닉네임은 와이어프레임 2.1의 인사말 `김난다 님, 오늘은 어떤 영화를 볼까요?`에서
 * 가져왔다. 측정된 값이 아니라 목업이다(screen-spec.md 3.3).
 */
import type { MyProfile } from '../types/user';

export const mockMyProfile: MyProfile = {
  userId: 1024,
  nickname: '김난다',
  onboardingCompleted: true,
  // 와이어프레임 3.1 취향 필름이 표시하는 기준 수치와 맞춘다
  stats: { frameCount: 100, popcornCount: 100, ratingCount: 100 },
};
