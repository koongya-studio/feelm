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
 *
 * 예외는 둘이다. 최근 검색어 · 8.1 알림은 와이어프레임이 그린 상태로 시작한다. 둘 다
 * 사용자가 지우거나 바꾸는 값이라 저장소 안에 있어야 변화가 남는다.
 *
 * 실제 API가 된 값은 여기서 빠진다. 차단 목록(API-020 ~ API-022)이 그렇게 나갔고, 알림
 * 설정은 계약에 자리가 없는 두 키만 남았다(업무 -394). 감상 기록(API-033 ~ API-035)도
 * 서버로 옮겨 가 이 파일에서 사라졌다(업무 -477).
 */
import { createSeedNotifications } from './notifications';
import type {
  NotificationView,
  PrivacySettingsView,
  UnspecifiedNotificationSettings,
} from '../types/unspecified';
import type { Rating, RatingUpdateInput } from '../types/watch';

type SessionState = {
  /**
   * API-027로 설정한 구독 OTT. `null`이면 아직 설정하지 않은 상태이며
   * 기준 목업의 구독 정보를 쓴다.
   */
  subscribedOttIds: number[] | null;
  /** API-047로 관심없음 처리한 영화 */
  dismissedMovieIds: number[];
  /** API-036 평가. 영화당 한 번만 반영된다 */
  ratings: Rating[];
  /**
   * API-037로 고친 **기준 목업 평가**. `ratingId` → 고친 값.
   *
   * 기준 평가(mocks/frames.ts `mockMyRatings`)를 `ratings`에 넣으면 안 된다. `ratings`는
   * 이번 세션에 새로 만든 평가라서 하나마다 프레임 1개와 팝콘 1알로 셈해진다
   * (api/taste.ts). 수정은 프레임을 만들지 않으므로(API-037 처리 규칙) 따로 둔다.
   * 이번 세션에 만든 평가를 고치면 `ratings`의 그 항목을 바로 고친다.
   */
  ratingEdits: Record<number, RatingUpdateInput>;
  /**
   * 4.1 · 4.2의 최근 검색어. 계약이 없다.
   *
   * 서버가 보관하는 값이 아니라 이 기기에서 무엇을 찾았는지이므로 API가 없는 것이
   * 자연스럽다. 실제 앱이라면 `localStorage`가 맞지만, 목업은 매번 같은 상태에서
   * 시작해야 하므로(이 파일 머리말) 다른 세션 상태와 같이 메모리에 둔다.
   */
  recentSearchQueries: string[];
  /**
   * 7.8-C에서 바꾼 알림 설정 중 **계약에 없는 둘**. `null`이면 기준 목업(mocks/settings.ts)이다.
   *
   * 계약의 세 키(API-017)는 서버가 들고 있어 여기 없다(업무 -394).
   */
  unspecifiedNotificationSettings: UnspecifiedNotificationSettings | null;
  /** 7.8-B에서 바꾼 공개 범위. `null`이면 기준 목업이다 */
  privacySettings: PrivacySettingsView | null;
  /**
   * 8.1 알림. 와이어프레임 네 건으로 시작한다. 누르면 읽음이 되고 그 상태가 남아야
   * 다시 들어왔을 때 표식이 사라진다(업무 -308).
   */
  notifications: NotificationView[];
  /**
   * 3.3 새 리포트 발급 화면을 이미 본 리포트. 2.1 `취향 리포트 보기`가 안 본 리포트면
   * 3.3을 거쳐 3.4로, 본 리포트면 3.4로 바로 간다(업무 -308).
   */
  seenReportIds: number[];
};

function emptyState(): SessionState {
  return {
    subscribedOttIds: null,
    dismissedMovieIds: [],
    ratings: [],
    ratingEdits: {},
    // 와이어프레임 4.1이 최근 검색어 세 개가 있는 상태를 그리므로 그 값으로 시작한다
    recentSearchQueries: ['케이퍼 무비', '크리스토퍼 놀란', 'SF 명작'],
    unspecifiedNotificationSettings: null,
    privacySettings: null,
    notifications: createSeedNotifications(),
    seenReportIds: [],
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

// ─────────────────────────────────────────── 설정 (업무 -307)

export function saveUnspecifiedNotificationSettings(
  settings: UnspecifiedNotificationSettings,
): void {
  state.unspecifiedNotificationSettings = { ...settings };
}

/** 바꾼 적 없으면 null. 기준 목업을 쓰라는 뜻이다 */
export function getUnspecifiedNotificationSettings(): UnspecifiedNotificationSettings | null {
  return state.unspecifiedNotificationSettings;
}

export function savePrivacySettings(settings: PrivacySettingsView): void {
  state.privacySettings = { ...settings };
}

/** 바꾼 적 없으면 null. 기준 목업을 쓰라는 뜻이다 */
export function getPrivacySettings(): PrivacySettingsView | null {
  return state.privacySettings;
}

// ─────────────────────────────────────────── 알림 · 리포트 (업무 -308)

export function getNotifications(): NotificationView[] {
  return state.notifications;
}

export function markNotificationRead(notificationId: number): void {
  state.notifications = state.notifications.map((item) =>
    item.notificationId === notificationId ? { ...item, read: true } : item,
  );
}

export function markReportSeen(reportId: number): void {
  if (!state.seenReportIds.includes(reportId)) {
    state.seenReportIds = [...state.seenReportIds, reportId];
  }
}

export function isReportSeen(reportId: number): boolean {
  return state.seenReportIds.includes(reportId);
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

// ─────────────────────────────────────────── 평가

/**
 * 평가는 영화당 한 번만 반영된다(2.5 안내 문구). 같은 영화를 다시 평가하면 덮어쓴다.
 * 이미 평가한 영화는 2.5가 수정(API-037, `updateSessionRating`)으로 보내므로 덮어쓰는
 * 경우는 실제 흐름에 없다(업무 -306).
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

export function findRatingByFrame(frameId: number): Rating | null {
  return state.ratings.find((rating) => rating.frame.frameId === frameId) ?? null;
}

/**
 * 평가 수정(API-037). 이번 세션에 만든 평가면 그 항목을 고치고 true를 돌려준다.
 * 기준 목업 평가라 여기 없으면 false다 — 그때는 `saveRatingEdit`을 쓴다.
 */
export function updateSessionRating(ratingId: number, input: RatingUpdateInput): boolean {
  const index = state.ratings.findIndex((rating) => rating.ratingId === ratingId);

  if (index === -1) {
    return false;
  }

  state.ratings[index] = { ...state.ratings[index], ...input };

  return true;
}

export function saveRatingEdit(ratingId: number, input: RatingUpdateInput): void {
  state.ratingEdits = { ...state.ratingEdits, [ratingId]: { ...input } };
}

/**
 * 기준 목업 평가에 고친 값을 얹는다. 고친 적 없으면 그대로 돌려준다.
 *
 * **기준 평가를 읽는 곳은 전부 이 함수를 거친다.** 영화 상세(2.4 · 2.5) · 프레임
 * 목록과 상세(3.1 · 3.1-A) · 내 평가 목록(7.6)이다. 한 곳이라도 빠지면 고친 별점이
 * 그 화면에서만 옛 값으로 보인다.
 */
export function withRatingEdit<T extends RatingUpdateInput & { ratingId: number }>(rating: T): T {
  const edit = state.ratingEdits[rating.ratingId];

  return edit ? { ...rating, ...edit } : rating;
}
