/*
 * 온보딩 타입. API-023 초기 취향 카드 목록 · API-024 초기 취향 제출
 * 계약의 사본이며 계약에 없는 필드를 넣지 않는다(frontend-structure.md 8.3).
 */

/** 저장되는 취향 값. API-024 상태값 OnboardingPreference */
export type OnboardingPreferenceValue = 'LIKE' | 'DISLIKE';

/** API-023 `items[]` */
export type OnboardingCard = {
  movieId: number;
  title: string;
  posterUrl: string | null;
  releaseYear: number;
  genres: string[];
  /** 팝콘 맛 코드. 표시명과 색상은 API-044로 조회한다 */
  flavorCode: string;
};

/** API-024 요청 본문의 `preferences[]` */
export type OnboardingPreference = {
  movieId: number;
  preference: OnboardingPreferenceValue;
};

/** API-024 응답 */
export type OnboardingSubmitResult = {
  onboardingCompleted: boolean;
  savedCount: number;
  skipped: boolean;
};
