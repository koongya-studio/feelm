/*
 * 사용자 타입. API-014 내 프로필 조회
 *
 * 홈(2.1)은 인사말에 쓰는 `nickname`만 사용한다. 나머지 필드는 MY 화면 업무에서
 * 필요해질 때 추가한다.
 */

export type MyProfileStats = {
  frameCount: number;
  popcornCount: number;
  ratingCount: number;
};

export type MyProfile = {
  userId: number;
  nickname: string;
  onboardingCompleted: boolean;
  stats: MyProfileStats;
};
