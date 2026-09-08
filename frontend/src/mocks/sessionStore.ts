/*
 * 세션 동안 유지되는 목업 상태.
 *
 * 지금까지 목업은 읽기 전용이라 관심없음 처리 · 평가 · 감상 기록이 화면을 벗어나면
 * 사라졌다. 여기에 모아 두면 화면들이 같은 상태를 보게 되어 앱처럼 동작한다
 * (업무 S15P21E106-262).
 *
 * **화면은 이 파일을 직접 import하지 않는다.** `src/api/`의 함수만 이 파일을 쓰고,
 * 화면은 지금처럼 `src/api/`만 호출한다(frontend-structure.md 7.1). 실제 연동
 * 단계에서는 `src/api/` 함수 본문에서 이 파일 호출만 걷어내면 된다.
 *
 * 저장 위치는 메모리다. 새로고침하면 초기 상태로 돌아간다(팀 결정 2026-09-08).
 * `sessionStorage`를 쓰지 않는 이유는 검증을 매번 같은 상태에서 시작하기 위해서다.
 *
 * 여기 담는 것은 **사용자가 이번 세션에 만든 변화**뿐이다. 기준 목업(mocks/의 나머지
 * 파일)은 그대로 두고, `src/api/`가 둘을 합쳐 응답을 만든다.
 */
import type { OnboardingPreference } from '../types/onboarding';
import type { Rating, WatchRecord } from '../types/watch';

type SessionState = {
  /** API-024로 저장한 초기 취향. 건너뛰면 빈 배열 */
  onboardingPreferences: OnboardingPreference[];
  onboardingSubmitted: boolean;
  /**
   * API-027로 설정한 구독 OTT. `null`이면 아직 설정하지 않은 상태이며
   * 기준 목업의 구독 정보를 쓴다.
   */
  subscribedOttIds: number[] | null;
  /** API-047로 관심없음 처리한 영화 */
  dismissedMovieIds: number[];
  /** API-033 · API-035 감상 기록 */
  watchRecords: WatchRecord[];
  /** API-036 평가. 영화당 한 번만 반영된다 */
  ratings: Rating[];
};

function emptyState(): SessionState {
  return {
    onboardingPreferences: [],
    onboardingSubmitted: false,
    subscribedOttIds: null,
    dismissedMovieIds: [],
    watchRecords: [],
    ratings: [],
  };
}

let state: SessionState = emptyState();

/** 검증과 개발 확인용. 화면에서는 쓰지 않는다 */
export function resetSessionStore(): void {
  state = emptyState();
}

// ─────────────────────────────────────────── 온보딩

export function saveOnboardingPreferences(preferences: OnboardingPreference[]): void {
  state.onboardingPreferences = preferences;
  state.onboardingSubmitted = true;
}

export function getOnboardingPreferences(): OnboardingPreference[] {
  return state.onboardingPreferences;
}

export function isOnboardingSubmitted(): boolean {
  return state.onboardingSubmitted;
}

// ─────────────────────────────────────────── 구독 OTT

export function setSubscribedOttIds(ottIds: number[]): void {
  state.subscribedOttIds = [...ottIds];
}

/** 아직 설정하지 않았으면 null. 기준 목업을 쓰라는 뜻이다 */
export function getSubscribedOttIds(): number[] | null {
  return state.subscribedOttIds;
}

// ─────────────────────────────────────────── 관심없음

export function addDismissedMovie(movieId: number): void {
  if (!state.dismissedMovieIds.includes(movieId)) {
    state.dismissedMovieIds.push(movieId);
  }
}

export function getDismissedMovieIds(): number[] {
  return state.dismissedMovieIds;
}

export function isDismissed(movieId: number): boolean {
  return state.dismissedMovieIds.includes(movieId);
}

// ─────────────────────────────────────────── 감상 기록

export function addWatchRecord(record: WatchRecord): void {
  state.watchRecords.push(record);
}

export function findWatchRecord(watchRecordId: number): WatchRecord | null {
  return state.watchRecords.find((record) => record.watchRecordId === watchRecordId) ?? null;
}

export function updateWatchRecord(watchRecordId: number, patch: Partial<WatchRecord>): void {
  const index = state.watchRecords.findIndex((record) => record.watchRecordId === watchRecordId);

  if (index !== -1) {
    state.watchRecords[index] = { ...state.watchRecords[index], ...patch };
  }
}

/** 영화의 가장 최근 감상 기록. 2.4가 감상 상태를 표시할 때 쓴다 */
export function findLatestWatchRecordByMovie(movieId: number): WatchRecord | null {
  return [...state.watchRecords].reverse().find((record) => record.movieId === movieId) ?? null;
}

// ─────────────────────────────────────────── 평가

/**
 * 평가는 영화당 한 번만 반영된다(2.5 안내 문구). 같은 영화를 다시 평가하면 덮어쓴다.
 * 계약상 수정은 API-037이지만 이 단계에서는 구분하지 않는다.
 */
export function saveRating(rating: Rating): void {
  const index = state.ratings.findIndex((saved) => saved.movieId === rating.movieId);

  if (index === -1) {
    state.ratings.push(rating);
  } else {
    state.ratings[index] = rating;
  }
}

export function getRatings(): Rating[] {
  return state.ratings;
}

export function findRatingByMovie(movieId: number): Rating | null {
  return state.ratings.find((rating) => rating.movieId === movieId) ?? null;
}

export function findRatingByFrame(frameId: number): Rating | null {
  return state.ratings.find((rating) => rating.frame.frameId === frameId) ?? null;
}
