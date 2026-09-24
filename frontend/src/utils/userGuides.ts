export const FIRST_USE_GUIDE = 'first-use-v1';
export const RECOMMENDATIONS_GUIDE = 'recommendations-overview-v1';
export const MOVIE_DETAIL_GUIDE = 'movie-detail-overview-v1';
export const PARTY_GUIDE = 'party-overview-v2';
export const FILM_GUIDE = 'film-overview-v2';

export type FirstUseStep = 'intro' | 'recommendations' | 'detail' | 'done';

function key(userId: number, guideId: string) {
  return `feelm:guide:${userId}:${guideId}`;
}

/** 안내 ID를 바꾸면 해당 기능의 새 버전을 한 번 더 안내할 수 있다. 계정마다 상태를 분리한다. */
export function hasSeenGuide(userId: number, guideId: string): boolean {
  try {
    return localStorage.getItem(key(userId, guideId)) === 'done';
  } catch {
    return false;
  }
}

export function completeGuide(userId: number, guideId: string): void {
  try {
    localStorage.setItem(key(userId, guideId), 'done');
  } catch {
    // 저장소가 막혀도 안내를 마칠 수 있어야 한다.
  }
}

/** 해당 기능 화면의 '다시 보기'에서 사용한다. 다른 기능의 안내 상태는 유지한다. */
export function restartGuide(userId: number, guideId: string): void {
  try {
    localStorage.removeItem(key(userId, guideId));
  } catch {
    // 저장소에 접근할 수 없으면 다음 진입에서 다시 안내할 수 있다.
  }
}

export function readFirstUseStep(userId: number): FirstUseStep | null {
  try {
    const value = localStorage.getItem(key(userId, FIRST_USE_GUIDE));
    return value === 'intro' ||
      value === 'recommendations' ||
      value === 'detail' ||
      value === 'done'
      ? value
      : null;
  } catch {
    return null;
  }
}

export function setFirstUseStep(userId: number, step: FirstUseStep): void {
  if (step === 'done') {
    completeGuide(userId, FIRST_USE_GUIDE);
    return;
  }
  try {
    localStorage.setItem(key(userId, FIRST_USE_GUIDE), step);
  } catch {
    // 세션 동안의 화면 전환은 계속 가능하다.
  }
}

/** 완료 화면은 온보딩 안에서만 접근하므로 다시 보기는 추천 단계부터 시작한다. */
export function restartFirstUse(userId: number): void {
  restartGuide(userId, RECOMMENDATIONS_GUIDE);
}
