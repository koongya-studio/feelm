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

/**
 * 저장소가 보관하는 감상 기록.
 *
 * API-033 응답(`WatchRecord`)에는 없지만 API-034 목록이 함께 내려주는 값
 * (`ott` · `watchedAt` · `ratingId`)을 붙인 것이다. 서버라면 한 행에 들어 있을 값이라
 * 목업도 한 곳에 모아 두고, `src/api/`가 API별 응답 모양으로 잘라 내려준다.
 */
export type StoredWatchRecord = WatchRecord & {
  /** 어느 OTT 링크를 눌렀는지 (API-033 요청 본문의 `ottId`) */
  ottId: number;
  /** 감상 확인에서 `봤어요`를 고른 시각 (API-035) */
  watchedAt: string | null;
  /** 이 기록으로 이어진 평가 (API-036) */
  ratingId: number | null;
};

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
  /** API-033 · API-034 · API-035 감상 기록 */
  watchRecords: StoredWatchRecord[];
  /** API-036 평가. 영화당 한 번만 반영된다 */
  ratings: Rating[];
  /**
   * 4.1 · 4.2의 최근 검색어. 계약이 없다.
   *
   * 서버가 보관하는 값이 아니라 이 기기에서 무엇을 찾았는지이므로 API가 없는 것이
   * 자연스럽다. 실제 앱이라면 `localStorage`가 맞지만, 목업은 매번 같은 상태에서
   * 시작해야 하므로(이 파일 머리말) 다른 세션 상태와 같이 메모리에 둔다.
   */
  recentSearchQueries: string[];
};

function emptyState(): SessionState {
  return {
    onboardingPreferences: [],
    onboardingSubmitted: false,
    subscribedOttIds: null,
    dismissedMovieIds: [],
    watchRecords: [],
    ratings: [],
    // 와이어프레임 4.1이 최근 검색어 세 개가 있는 상태를 그리므로 그 값으로 시작한다
    recentSearchQueries: ['케이퍼 무비', '크리스토퍼 놀란', 'SF 명작'],
  };
}

/**
 * 최근 검색어 보관 개수.
 *
 * `[?]` 와이어프레임 4.1은 세 개를 그렸지만 상한인지 그때의 개수인지 알 수 없다.
 * 한 줄에 들어가는 만큼으로 잡았다.
 */
const MAX_RECENT_SEARCH_QUERIES = 10;

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

// ─────────────────────────────────────────── 최근 검색어

/** 최신이 앞이다. 같은 검색어를 다시 찾으면 중복을 만들지 않고 앞으로 옮긴다 */
export function addRecentSearchQuery(query: string): void {
  const trimmed = query.trim();

  if (!trimmed) return;

  const rest = state.recentSearchQueries.filter((item) => item !== trimmed);
  state.recentSearchQueries = [trimmed, ...rest].slice(0, MAX_RECENT_SEARCH_QUERIES);
}

export function getRecentSearchQueries(): string[] {
  return state.recentSearchQueries;
}

export function removeRecentSearchQuery(query: string): void {
  state.recentSearchQueries = state.recentSearchQueries.filter((item) => item !== query);
}

export function clearRecentSearchQueries(): void {
  state.recentSearchQueries = [];
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

export function addWatchRecord(record: StoredWatchRecord): void {
  state.watchRecords.push(record);
}

/** API-034가 목록으로 내려줄 원본. 정렬은 `src/api/`가 한다 */
export function getWatchRecords(): StoredWatchRecord[] {
  return state.watchRecords;
}

export function findWatchRecord(watchRecordId: number): StoredWatchRecord | null {
  return state.watchRecords.find((record) => record.watchRecordId === watchRecordId) ?? null;
}

export function updateWatchRecord(watchRecordId: number, patch: Partial<StoredWatchRecord>): void {
  const index = state.watchRecords.findIndex((record) => record.watchRecordId === watchRecordId);

  if (index !== -1) {
    state.watchRecords[index] = { ...state.watchRecords[index], ...patch };
  }
}

/** 영화의 가장 최근 감상 기록. 2.4가 감상 상태를 표시할 때 쓴다 */
export function findLatestWatchRecordByMovie(movieId: number): StoredWatchRecord | null {
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
